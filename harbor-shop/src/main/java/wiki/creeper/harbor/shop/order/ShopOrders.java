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
import wiki.creeper.harbor.shop.data.ShopRepository;
import wiki.creeper.harbor.shop.donor.DonorManager;
import wiki.creeper.harbor.shop.pearl.PearlLedger;
import wiki.creeper.harbor.shop.pearl.PearlManager;
import wiki.creeper.harbor.shop.pearl.ShopReject;

/**
 * 게임 안 진주 사용 주문 (docs 20 12.4 '게임 안 구매'·'선물하기'·'닉네임 표시 변경권'·'첫 항해 패키지', '6. BM 기획' 3.6 크리에이터 코드).
 * 버튼마다 만든 order_id(UUID v7)를 참조 ID로 써서 연타·지연 재전송이 와도 한 번만 차감된다. 주문 기록·진주 차감·소유 기록이 한 트랜잭션이다.
 *
 * <p>결과 future는 메인 스레드에서 완료되고, 실패면 보여 줄 문구 키를 담는다.
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

    // ------------------------------------------------------------------ 진열

    /** 지금 진주 상점에 진열된 코스메틱인지 (상시·이번 주 로테이션·이번 시즌 한정). */
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

    /** 구매 조건 (전설 어종 기념 스킨: 그 어종을 직접 잡았어야 함, Collection codex_total). 접속 중인 플레이어만 확인할 수 있다. */
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

    // ------------------------------------------------------------------ 코스메틱 구매

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
                            ShopRepository.insertOwned(
                                    tx.connection(), uuid, def.id(), "SHOP", "order:" + orderId);
                            return Set.of(def.id());
                        }),
                uuid,
                "SHOP");
    }

    // ------------------------------------------------------------------ 묶음 (첫 항해 패키지)

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
                            tx.requireUnlocked(uuid);
                            // 계정당 구매 한도 (account_limit, 추가 제안): 주문 기록으로 센다
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
                            return Set.copyOf(added);
                        }),
                uuid,
                "BUNDLE");
    }

    // ------------------------------------------------------------------ 선물하기

    /**
     * 선물한다: 산 사람이 진주를 내고 받는 사람의 소유 기록에 source = GIFT로 남긴다. 받는 사람이 이미 가졌거나 구매 조건(기념 스킨)을 못 채우면 거부한다.
     * 하루 5회, 플레이 5시간 미만 계정 간 선물 금지 (제안값). 기념 스킨은 받는 사람이 이 서버에 접속 중일 때만 조건을 확인할 수 있다.
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
                            ShopRepository.insertOwned(
                                    tx.connection(),
                                    recipient,
                                    def.id(),
                                    "GIFT",
                                    "order:" + orderId);
                            ShopRepository.insertNotice(
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

    // ------------------------------------------------------------------ 닉네임 표시 변경권

    /** 표시 이름 형식 검사 (한글·영문·숫자·밑줄, 길이 제안값). */
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

    // ------------------------------------------------------------------ 크리에이터 코드

    /** 크리에이터 코드를 등록한다 (시즌 2 이후 기능). 다음 충전부터 정산 기록에 들어가고, 구매자에게 칭호를 준다. */
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

    // ------------------------------------------------------------------ 공용

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
