package wiki.creeper.harbor.economy.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.economy.api.event.AuctionListedEvent;
import wiki.creeper.harbor.economy.content.AuctionRefs;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.npc.api.ReputationService;

/**
 * Auction listing (docs 20 7.4 listing). Checks (slots, tradable, listing price range, new account)
 * → confirmation screen → take the item from the hand and hold it → in one transaction, insert
 * auction_listing + listing fee AUCTION_LIST_FEE + fish_ledger ACTIVE → IN_AUCTION. On failure the
 * item is returned.
 */
final class AuctionLister {

    /** Listing candidate: reference price and category of the held item. */
    private record Candidate(
            String itemId,
            String name,
            String category,
            Grade grade,
            long unitRef,
            @Nullable UUID uid,
            boolean trap) {}

    private static final StatKey LIST_FEE = StatKey.of("auction_list_fee");
    private static final StatKey SALE_FEE = StatKey.of("auction_fee");
    private static final StatKey SLOTS = StatKey.of("auction_slots");

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final MarketEngine market;
    private final MailManager mail;
    private final TradeGuard guard;
    private final AuctionHouse house;
    private final AuctionListings listings;

    AuctionLister(
            HarborApi api,
            Tasks tasks,
            Logger log,
            MarketEngine market,
            MailManager mail,
            TradeGuard guard,
            AuctionHouse house,
            AuctionListings listings) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.market = market;
        this.mail = mail;
        this.guard = guard;
        this.house = house;
        this.listings = listings;
    }

    private EconomyBalance balance() {
        return house.balance();
    }

    private Optional<Candidate> candidate(ItemStack stack) {
        ContentSnapshot snapshot = api.content().snapshot();
        Optional<HarborItem> read = api.items().read(stack);
        if (read.isEmpty()) {
            return Optional.empty();
        }
        boolean trap =
                api.service(FishingService.class)
                        .map(f -> f.traps().isTrapCatch(stack))
                        .orElse(false);
        EconomyBalance b = balance();
        return switch (read.get()) {
            case HarborItem.Fish fish -> {
                Optional<FishDef> def = snapshot.get(FishDef.TYPE, fish.species().id());
                if (def.isEmpty() || def.get().releaseOnly()) {
                    yield Optional.empty();
                }
                OptionalLong ref =
                        AuctionMath.fishRef(
                                def.get().grade(), market.basePrice(fish), b.auctionMythicRef());
                yield ref.isEmpty()
                        ? Optional.empty()
                        : Optional.of(
                                new Candidate(
                                        fish.species().toString(),
                                        def.get().name(),
                                        "fish",
                                        def.get().grade(),
                                        ref.getAsLong(),
                                        fish.uid(),
                                        trap));
            }
            case HarborItem.Plain plain -> {
                Optional<ItemDef> def = snapshot.get(ItemDef.TYPE, plain.id().id());
                if (def.isEmpty() || !def.get().tradeable()) {
                    yield Optional.empty();
                }
                OptionalLong ref =
                        AuctionMath.itemRef(
                                def.get().sellPrice(),
                                def.get().buyPrice(),
                                snapshot.single(AuctionRefs.TYPE).of(plain.id()));
                yield ref.isEmpty()
                        ? Optional.empty()
                        : Optional.of(
                                new Candidate(
                                        plain.id().toString(),
                                        def.get().name(),
                                        category(def.get().type()),
                                        def.get().grade(),
                                        ref.getAsLong(),
                                        null,
                                        false));
            }
        };
    }

    static String category(ItemDef.Type type) {
        return switch (type) {
            case ROD, LINE, FLOAT, REEL, GEAR, TOOL, BOAT, TRAP -> "gear";
            case BAIT -> "bait";
            case MATERIAL -> "material";
            case FOOD -> "food";
            case CURRENCY, TOKEN -> "other";
        };
    }

    private int slots(UUID player) {
        int tier = api.service(ReputationService.class).map(r -> r.tier(player)).orElse(1);
        return AuctionMath.slots(
                balance().auctionSlots(tier), api.modifiers().apply(player, SLOTS, 0));
    }

    /** /경매 등록 <price>: lists the held item as a whole stack (confirmation screen). */
    void startListing(Player player, long price) {
        UUID uuid = player.getUniqueId();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            api.messages().send(player, "economy.auction.hold_item");
            return;
        }
        if (ItemLock.locked(held)) {
            api.messages().send(player, "economy.auction.locked");
            return;
        }
        if (!house.nearBoard(player)) {
            api.messages().send(player, "economy.auction.board_only");
            return;
        }
        EconomyBalance b = balance();
        HarborPlayer hp = api.players().get(player);
        if (AuctionMath.newAccountBlocked(hp.playtime(), b.auctionNewAccountHours())) {
            api.messages()
                    .send(
                            player,
                            "economy.auction.new_account",
                            Placeholder.unparsed(
                                    "hours", String.valueOf(b.auctionNewAccountHours())));
            return;
        }
        Optional<Candidate> found = candidate(held);
        if (found.isEmpty()) {
            api.messages().send(player, "economy.auction.not_tradeable");
            return;
        }
        Candidate cand = found.get();
        int amount = held.getAmount();
        long ref = cand.unitRef() * amount;
        AuctionMath.Bounds bounds =
                AuctionMath.bounds(ref, b.auctionMinRatio(), b.auctionMaxRatio());
        if (!bounds.contains(price)) {
            api.messages()
                    .send(
                            player,
                            "economy.auction.price_range",
                            Placeholder.unparsed("min", String.valueOf(bounds.min())),
                            Placeholder.unparsed("max", String.valueOf(bounds.max())));
            return;
        }
        int slots = slots(uuid);
        double listRate =
                AuctionMath.clampRate(api.modifiers().apply(uuid, LIST_FEE, b.auctionListFee()));
        double saleRate =
                AuctionMath.clampRate(api.modifiers().apply(uuid, SALE_FEE, b.auctionSaleFee()));
        long fee = AuctionMath.listFee(price, listRate);
        AuctionMath.Settlement settle = AuctionMath.settle(price, saleRate);
        ItemStack snapshot = held.clone();
        int slot = player.getInventory().getHeldItemSlot();
        // Read reference prices before listing (lowest unit price, recent sale average), then show
        // the confirmation screen
        var _ =
                listings.priceInfo(
                                cand.itemId(),
                                house.balance()
                                        .balance()
                                        .integer("economy.auction.price_info_days", 7))
                        .exceptionally(
                                e ->
                                        new AuctionListings.PriceInfo(
                                                java.util.OptionalLong.empty(),
                                                java.util.OptionalLong.empty(),
                                                0,
                                                0))
                        .thenAccept(
                                info -> {
                                    if (player.isOnline()) {
                                        confirmListing(
                                                player, slot, snapshot, cand, amount, price, ref,
                                                fee, settle, saleRate, slots, info);
                                    }
                                });
    }

    private void confirmListing(
            Player player,
            int slot,
            ItemStack snapshot,
            Candidate cand,
            int amount,
            long price,
            long ref,
            long fee,
            AuctionMath.Settlement settle,
            double saleRate,
            int slots,
            AuctionListings.PriceInfo info) {
        EconomyBalance b = balance();
        List<net.kyori.adventure.text.Component> body = new java.util.ArrayList<>();
        body.add(
                api.messages()
                        .get(
                                "economy.auction.list_body",
                                Placeholder.unparsed("name", cand.name()),
                                Placeholder.unparsed("amount", String.valueOf(amount)),
                                Placeholder.unparsed("price", String.valueOf(price)),
                                Placeholder.unparsed("fee", String.valueOf(fee)),
                                Placeholder.unparsed("sale_fee", String.valueOf(settle.fee())),
                                Placeholder.unparsed("net", String.valueOf(settle.sellerNet())),
                                Placeholder.unparsed(
                                        "hours", String.valueOf(b.auctionDurationHours()))));
        long unit = price / Math.max(1, amount);
        body.add(
                api.messages()
                        .get(
                                "economy.auction.price_ref.mine",
                                Placeholder.unparsed("unit", String.valueOf(unit))));
        body.add(
                info.lowestUnit().isPresent()
                        ? api.messages()
                                .get(
                                        "economy.auction.price_ref.lowest",
                                        Placeholder.unparsed(
                                                "unit",
                                                String.valueOf(info.lowestUnit().getAsLong())))
                        : api.messages().get("economy.auction.price_ref.no_listing"));
        body.add(
                info.averageUnit().isPresent()
                        ? api.messages()
                                .get(
                                        "economy.auction.price_ref.average",
                                        Placeholder.unparsed(
                                                "unit",
                                                String.valueOf(info.averageUnit().getAsLong())),
                                        Placeholder.unparsed("count", String.valueOf(info.sold())),
                                        Placeholder.unparsed("days", String.valueOf(info.days())))
                        : api.messages()
                                .get(
                                        "economy.auction.price_ref.no_sales",
                                        Placeholder.unparsed("days", String.valueOf(info.days()))));
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("economy.auction.list_title"),
                        body,
                        api.messages().get("economy.auction.yes"),
                        api.messages().get("economy.auction.no"),
                        ok -> {
                            if (ok) {
                                list(
                                        player, slot, snapshot, cand, price, ref, fee, saleRate,
                                        slots);
                            }
                        });
    }

    private void list(
            Player player,
            int slot,
            ItemStack expected,
            Candidate cand,
            long price,
            long ref,
            long fee,
            double saleRate,
            int slots) {
        UUID uuid = player.getUniqueId();
        PlayerInventory inv = player.getInventory();
        ItemStack now = inv.getItem(slot);
        if (now == null
                || now.getAmount() != expected.getAmount()
                || !now.isSimilar(expected)
                || inv.getHeldItemSlot() != slot) {
            api.messages().send(player, "economy.auction.changed");
            return;
        }
        Optional<TradeGuard.Ticket> ticket = guard.begin(uuid);
        if (ticket.isEmpty()) {
            api.messages().send(player, "economy.busy");
            return;
        }
        // Hold: take it out of the inventory
        ItemStack custody = now.clone();
        inv.setItem(slot, null);
        byte[] data = custody.serializeAsBytes();
        EconomyBalance b = balance();
        Instant expires = Instant.now().plus(Duration.ofHours(b.auctionDurationHours()));
        String server = api.infra().serverId();
        String name = player.getName();
        AtomicLong listingId = new AtomicLong();
        AtomicReference<String> reason = new AtomicReference<>("failed");
        UUID uid = cand.uid();
        SqlConsumer work =
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "SELECT id FROM auction_listing WHERE seller = ? AND state = 'ACTIVE' FOR UPDATE")) {
                        ps.setString(1, uuid.toString());
                        int count = 0;
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                count++;
                            }
                        }
                        if (count >= slots) {
                            reason.set("slots");
                            throw new LedgerException(LedgerError.REJECTED, "경매 슬롯이 가득 참");
                        }
                    }
                    if (uid != null
                            && !FishLedger.transition(
                                    c, uid, FishLedger.State.ACTIVE, FishLedger.State.IN_AUCTION)) {
                        reason.set("ledger");
                        throw new LedgerException(
                                LedgerError.REJECTED, "장부 상태가 ACTIVE가 아님: " + uid);
                    }
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO auction_listing (seller, seller_name, item_uuid, item_id, item_name, category, grade,"
                                            + " trap_src, item_data, amount, price, ref_price, fee, sale_fee_rate, server, expires_at)"
                                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, name);
                        ps.setString(3, uid == null ? null : uid.toString());
                        ps.setString(4, cand.itemId());
                        ps.setString(5, truncate(cand.name(), 64));
                        ps.setString(6, cand.category());
                        ps.setString(7, cand.grade().key());
                        ps.setBoolean(8, cand.trap());
                        ps.setBytes(9, data);
                        ps.setInt(10, custody.getAmount());
                        ps.setLong(11, price);
                        ps.setLong(12, ref);
                        ps.setLong(13, fee);
                        ps.setDouble(14, saleRate);
                        ps.setString(15, server);
                        ps.setTimestamp(16, Timestamp.from(expires));
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            if (keys.next()) {
                                listingId.set(keys.getLong(1));
                            }
                        }
                    } catch (SQLIntegrityConstraintViolationException e) {
                        reason.set("ledger");
                        throw new LedgerException(LedgerError.REJECTED, "같은 고유 아이템이 이미 등록됨");
                    }
                };
        List<LedgerEntry> entries =
                fee > 0
                        ? List.of(
                                new LedgerEntry(
                                        uuid,
                                        Currency.GOLD,
                                        -fee,
                                        LedgerReason.AUCTION_LIST_FEE,
                                        "alist:" + UUID.randomUUID()))
                        : List.of();
        var _ =
                ticket.get()
                        .track(Tx.run(api, tasks, entries, work))
                        .thenAccept(
                                result -> {
                                    LedgerError error = Tx.error(result);
                                    if (error != null) {
                                        mail.giveOrMail(
                                                uuid,
                                                List.of(custody),
                                                "auction_fail",
                                                "alist_fail:" + UUID.randomUUID());
                                        if (player.isOnline()) {
                                            api.messages()
                                                    .send(
                                                            player,
                                                            error == LedgerError.INSUFFICIENT_FUNDS
                                                                    ? "economy.auction.no_gold"
                                                                    : "economy.auction.list_failed."
                                                                            + reason.get());
                                        }
                                        return;
                                    }
                                    listings.invalidate();
                                    log.info(
                                            "경매 등록 #{} {} {} x{} {}G (등록비 {}G)",
                                            listingId.get(),
                                            name,
                                            cand.itemId(),
                                            custody.getAmount(),
                                            price,
                                            fee);
                                    Bukkit.getPluginManager()
                                            .callEvent(
                                                    new AuctionListedEvent(
                                                            uuid,
                                                            listingId.get(),
                                                            cand.itemId(),
                                                            custody.getAmount(),
                                                            price,
                                                            fee));
                                    if (player.isOnline()) {
                                        api.messages()
                                                .send(
                                                        player,
                                                        "economy.auction.listed",
                                                        Placeholder.unparsed("name", cand.name()),
                                                        Placeholder.unparsed(
                                                                "price", String.valueOf(price)));
                                    }
                                });
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
