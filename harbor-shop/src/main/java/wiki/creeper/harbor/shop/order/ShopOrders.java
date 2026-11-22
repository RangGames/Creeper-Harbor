package wiki.creeper.harbor.shop.order;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.collection.api.CodexScope;
import wiki.creeper.harbor.collection.api.CodexService;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.content.BundleDef;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.content.CreatorDef;
import wiki.creeper.harbor.shop.content.RotationPlan;
import wiki.creeper.harbor.shop.content.ShopSettings;
import wiki.creeper.harbor.shop.cosmetic.CosmeticManager;
import wiki.creeper.harbor.shop.data.NoticeRepository;
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.donor.DonorManager;
import wiki.creeper.harbor.shop.pearl.PearlLedger;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.pearl.ShopReject;

/**
 * In-game pearl spending orders (docs 20 12.4 'in-game purchase', 'gifting', 'nickname display
 * change ticket', 'first voyage package'; '6. BM plan' 3.6 creator codes). The order_id (UUID v7)
 * created per button is used as the reference ID, so repeated clicks or delayed resends deduct only
 * once. Order record, pearl deduction and ownership record are one transaction.
 *
 * <p>The result future completes on the main thread and, on failure, holds the message key to show.
 */
public final class ShopOrders {

    private static final Pattern NAME = Pattern.compile("[가-힣A-Za-z0-9_]+");

    private final ShopRuntime rt;
    private final PearlManager pearls;
    private final CosmeticManager cosmetics;
    private final DonorManager donors;

    public ShopOrders(
            ShopRuntime rt, PearlManager pearls, CosmeticManager cosmetics, DonorManager donors) {
        this.rt = rt;
        this.pearls = pearls;
        this.cosmetics = cosmetics;
        this.donors = donors;
    }

    // ------------------------------------------------------------------ listings

    /**
     * Whether the cosmetic is currently listed in the pearl shop (permanent, this week's rotation,
     * this season's limited).
     */
    public boolean onSale(CosmeticDef def) {
        if (!def.sellableIn(rt.season())) {
            return false;
        }
        if (def.availability() == CosmeticDef.Availability.ROTATION) {
            return rotation().contains(def.id());
        }
        return true;
    }

    public List<HarborId> rotation() {
        return rt.snapshot().single(RotationPlan.TYPE).itemsFor(rt.clock().week());
    }

    /**
     * Purchase condition (legendary fish commemorative skin: must have caught that fish yourself,
     * Collection codex_total). Only checkable for online players.
     */
    public boolean meetsRequirement(UUID player, CosmeticDef def) {
        Optional<FishId> fish = def.fishRequirement();
        if (fish.isEmpty()) {
            return true;
        }
        return rt.api()
                .service(CodexService.class)
                .map(codex -> codex.isRegistered(player, fish.get(), CodexScope.TOTAL))
                .orElse(false);
    }

    // ------------------------------------------------------------------ cosmetic purchase

    public CompletableFuture<Optional<String>> buyCosmetic(
            Player player, CosmeticDef def, UUID orderId) {
        UUID uuid = player.getUniqueId();
        if (!onSale(def)) {
            return done("shop.error.not_on_sale");
        }
        if (cosmetics.owns(uuid, def.id())) {
            return done("shop.error.owned");
        }
        if (!meetsRequirement(uuid, def)) {
            return done("shop.error.requirement");
        }
        return finish(
                pearls.transact(
                        tx -> {
                            ShopRepository.lockWallet(tx.connection(), uuid);
                            tx.requireUnlocked(uuid);
                            if (!ShopRepository.insertOrder(
                                    tx.connection(),
                                    orderId,
                                    uuid,
                                    "COSMETIC",
                                    def.id().toString(),
                                    def.price(),
                                    null)) {
                                throw new ShopReject("shop.error.duplicate");
                            }
                            if (ShopRepository.ownsRow(tx.connection(), uuid, def.id())) {
                                throw new ShopReject("shop.error.owned");
                            }
                            tx.apply(
                                    PearlLedger.Change.debit(
                                            uuid,
                                            def.price(),
                                            LedgerReason.SHOP_BUY,
                                            "order:" + orderId));
                            if (!ShopRepository.insertOwned(
                                    tx.connection(), uuid, def.id(), "SHOP", "order:" + orderId)) {
                                // Rolled back, so the charge is cancelled too
                                throw new ShopReject("shop.error.owned");
                            }
                            return Set.of(def.id());
                        }),
                uuid,
                "SHOP");
    }

    // ------------------------------------------------------------------ bundles (first voyage
    // package)

    public boolean bundleOnSale(BundleDef bundle) {
        return bundle.onSale(rt.season(), rt.clock().week());
    }

    public CompletableFuture<Optional<String>> buyBundle(
            Player player, BundleDef bundle, UUID orderId) {
        UUID uuid = player.getUniqueId();
        if (!bundleOnSale(bundle)) {
            return done("shop.error.not_on_sale");
        }
        if (cosmetics.owned(uuid).containsAll(bundle.items())) {
            return done("shop.error.owned");
        }
        return finish(
                pearls.transact(
                        tx -> {
                            ShopRepository.lockWallet(tx.connection(), uuid);
                            tx.requireUnlocked(uuid);
                            // Per-account purchase limit (account_limit, proposed): counted from
                            // order records
                            if (bundle.accountLimit() > 0
                                    && ShopRepository.countOrders(
                                                    tx.connection(),
                                                    uuid,
                                                    "BUNDLE",
                                                    bundle.id().toString())
                                            >= bundle.accountLimit()) {
                                throw new ShopReject("shop.error.account_limit");
                            }
                            if (!ShopRepository.insertOrder(
                                    tx.connection(),
                                    orderId,
                                    uuid,
                                    "BUNDLE",
                                    bundle.id().toString(),
                                    bundle.price(),
                                    null)) {
                                throw new ShopReject("shop.error.duplicate");
                            }
                            tx.apply(
                                    PearlLedger.Change.debit(
                                            uuid,
                                            bundle.price(),
                                            LedgerReason.SHOP_BUY,
                                            "order:" + orderId));
                            Set<HarborId> added = new HashSet<>();
                            for (HarborId item : bundle.items()) {
                                if (ShopRepository.insertOwned(
                                        tx.connection(),
                                        uuid,
                                        item,
                                        "BUNDLE",
                                        "order:" + orderId)) {
                                    added.add(item);
                                }
                            }
                            if (added.isEmpty()) {
                                // All of it was obtained some other way in the meantime
                                throw new ShopReject("shop.error.owned");
                            }
                            return Set.copyOf(added);
                        }),
                uuid,
                "BUNDLE");
    }

    // ------------------------------------------------------------------ gifting

    /**
     * Gifts: the buyer pays the pearls and the recipient's ownership record gets source = GIFT.
     * Rejected if the recipient already owns it or fails the purchase condition (commemorative
     * skin). 5 per day; gifts between accounts with under 5 hours of play are forbidden (proposed
     * value). Commemorative skin conditions can only be checked while the recipient is online on
     * this server.
     */
    public CompletableFuture<Optional<String>> gift(
            Player sender, String recipientName, CosmeticDef def, UUID orderId) {
        ShopSettings settings = rt.settings();
        UUID uuid = sender.getUniqueId();
        if (!settings.features().gifting()) {
            return done("shop.gift.disabled");
        }
        if (!def.giftable() || !onSale(def)) {
            return done("shop.gift.not_giftable");
        }
        Player online = Bukkit.getPlayerExact(recipientName);
        if (def.fishRequirement().isPresent()
                && (online == null || !meetsRequirement(online.getUniqueId(), def))) {
            return done("shop.gift.requirement");
        }
        long minPlay = Duration.ofHours(settings.gift().minPlaytimeHours()).toSeconds();
        ZonedDateTime dayStart = rt.api().time().nextDailyReset().minusDays(1);
        Instant since = dayStart.toInstant();
        return pearls.transact(
                        tx -> {
                            ShopRepository.lockWallet(tx.connection(), uuid);
                            tx.requireUnlocked(uuid);
                            UUID recipient =
                                    online != null
                                            ? online.getUniqueId()
                                            : ShopRepository.uuidByName(
                                                            tx.connection(), recipientName)
                                                    .orElseThrow(
                                                            () ->
                                                                    new ShopReject(
                                                                            "shop.gift.unknown"));
                            if (recipient.equals(uuid)) {
                                throw new ShopReject("shop.gift.self");
                            }
                            if (ShopRepository.playtime(tx.connection(), uuid) < minPlay
                                    || ShopRepository.playtime(tx.connection(), recipient)
                                            < minPlay) {
                                throw new ShopReject("shop.gift.playtime");
                            }
                            if (ShopRepository.countOrdersSince(
                                            tx.connection(), uuid, "GIFT", since)
                                    >= settings.gift().dailyLimit()) {
                                throw new ShopReject("shop.gift.daily_limit");
                            }
                            if (ShopRepository.ownsRow(tx.connection(), recipient, def.id())) {
                                throw new ShopReject("shop.gift.owned");
                            }
                            if (!ShopRepository.insertOrder(
                                    tx.connection(),
                                    orderId,
                                    uuid,
                                    "GIFT",
                                    def.id().toString(),
                                    def.price(),
                                    recipient)) {
                                throw new ShopReject("shop.error.duplicate");
                            }
                            tx.apply(
                                    PearlLedger.Change.debit(
                                            uuid,
                                            def.price(),
                                            LedgerReason.GIFT,
                                            "order:" + orderId));
                            // Concurrent gifts to the recipient are not serialized by the buyer's
                            // lock: decide by the insert result and roll back
                            if (!ShopRepository.insertOwned(
                                    tx.connection(),
                                    recipient,
                                    def.id(),
                                    "GIFT",
                                    "order:" + orderId)) {
                                throw new ShopReject("shop.gift.owned");
                            }
                            NoticeRepository.insertNotice(
                                    tx.connection(),
                                    recipient,
                                    "GIFT",
                                    0,
                                    sender.getName() + "|" + def.id());
                            return recipient;
                        })
                .thenApply(
                        outcome ->
                                switch (outcome.result()) {
                                    case Result.Ok<UUID, PearlManager.Failure>(UUID recipient) -> {
                                        rt.notifyPlayer(recipient, "GIFT", 0);
                                        rt.log()
                                                .info(
                                                        "선물 {} → {} {}",
                                                        sender.getName(),
                                                        recipient,
                                                        def.id());
                                        yield Optional.<String>empty();
                                    }
                                    case Result.Err<UUID, PearlManager.Failure>(
                                                    PearlManager.Failure f) ->
                                            Optional.of(f.messageKey());
                                });
    }

    // ------------------------------------------------------------------ nickname display change
    // ticket

    /**
     * Display name format check (Hangul, Latin letters, digits, underscore; length is a proposed
     * value).
     */
    public static boolean validName(String name, ShopSettings.NameChange rule) {
        int length = name.codePointCount(0, name.length());
        return length >= rule.minLength()
                && length <= rule.maxLength()
                && NAME.matcher(name).matches();
    }

    public CompletableFuture<Optional<String>> changeName(
            Player player, String name, UUID orderId) {
        ShopSettings settings = rt.settings();
        UUID uuid = player.getUniqueId();
        if (!settings.features().nameChange()) {
            return done("shop.name.disabled");
        }
        if (!validName(name, settings.nameChange())) {
            return done("shop.name.invalid");
        }
        int price = settings.nameChange().price();
        return pearls.transact(
                        tx -> {
                            ShopRepository.lockWallet(tx.connection(), uuid);
                            tx.requireUnlocked(uuid);
                            if (ShopRepository.nameTaken(tx.connection(), uuid, name)) {
                                throw new ShopReject("shop.name.taken");
                            }
                            if (!ShopRepository.insertOrder(
                                    tx.connection(),
                                    orderId,
                                    uuid,
                                    "NAME_CHANGE",
                                    name,
                                    price,
                                    null)) {
                                throw new ShopReject("shop.error.duplicate");
                            }
                            tx.apply(
                                    PearlLedger.Change.debit(
                                            uuid,
                                            price,
                                            LedgerReason.NAME_CHANGE,
                                            "order:" + orderId));
                            ShopRepository.setDisplayName(tx.connection(), uuid, name);
                            return name;
                        })
                .thenApply(
                        outcome ->
                                switch (outcome.result()) {
                                    case Result.Ok<String, PearlManager.Failure>(String n) -> {
                                        rt.update(uuid, p -> p.withDisplayName(n));
                                        donors.applyName(player);
                                        yield Optional.<String>empty();
                                    }
                                    case Result.Err<String, PearlManager.Failure>(
                                                    PearlManager.Failure f) ->
                                            Optional.of(f.messageKey());
                                });
    }

    // ------------------------------------------------------------------ creator codes

    /**
     * Registers a creator code (post-season-2 feature). Included in settlement records from the
     * next top-up, and grants the buyer a title.
     */
    public CompletableFuture<Optional<String>> supportCreator(Player player, String rawCode) {
        if (!rt.settings().features().creatorCodes()) {
            return done("shop.creator.disabled");
        }
        String code = rawCode.toUpperCase(java.util.Locale.ROOT);
        Optional<CreatorDef> creator =
                rt.snapshot().all(CreatorDef.TYPE).values().stream()
                        .filter(c -> c.code().equals(code))
                        .findFirst();
        if (creator.isEmpty()) {
            return done("shop.creator.unknown");
        }
        UUID uuid = player.getUniqueId();
        return rt.db(
                        c -> {
                            try (var ps =
                                    c.prepareStatement(
                                            "INSERT INTO creator_support (uuid, code) VALUES (?, ?)"
                                                    + " ON DUPLICATE KEY UPDATE code = VALUES(code), set_at = NOW(3)")) {
                                ps.setString(1, uuid.toString());
                                ps.setString(2, code);
                                ps.executeUpdate();
                            }
                            return code;
                        })
                .thenApply(
                        c -> {
                            rt.update(uuid, p -> p.withCreator(c));
                            String title = creator.get().supporterTitle();
                            if (title != null) {
                                var _ =
                                        SeasonServices.titles()
                                                .grant(
                                                        uuid,
                                                        TitleId.of(title),
                                                        "creator:" + code + ":" + uuid);
                            }
                            return Optional.<String>empty();
                        });
    }

    // ------------------------------------------------------------------ shared

    private static CompletableFuture<Optional<String>> done(String key) {
        return CompletableFuture.completedFuture(Optional.of(key));
    }

    private CompletableFuture<Optional<String>> finish(
            CompletableFuture<PearlManager.Outcome<Set<HarborId>>> future,
            UUID player,
            String source) {
        return future.thenApply(
                outcome ->
                        switch (outcome.result()) {
                            case Result.Ok<Set<HarborId>, PearlManager.Failure>(
                                            Set<HarborId> added) -> {
                                cosmetics.granted(player, added, source);
                                yield Optional.<String>empty();
                            }
                            case Result.Err<Set<HarborId>, PearlManager.Failure>(
                                            PearlManager.Failure f) ->
                                    Optional.of(f.messageKey());
                        });
    }
}
