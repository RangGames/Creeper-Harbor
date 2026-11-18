package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.economy.api.AuctionListingInfo;
import wiki.creeper.harbor.economy.api.AuctionService;
import wiki.creeper.harbor.economy.api.event.AuctionListedEvent;
import wiki.creeper.harbor.economy.api.event.AuctionSoldEvent;
import wiki.creeper.harbor.economy.content.AuctionRefs;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.economy.content.StationDef;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.npc.api.ReputationService;

/**
 * 경매장 (docs 20 7.4 경매장 규칙·등록·구매·만료). 아이템은 한 순간에 한 곳(인벤토리 → 경매 행 → 우편함)에만 있다.
 *
 * <ul>
 *   <li>등록: 조건 검사(슬롯·거래 가능·등록가 범위·신규 계정) → 손의 아이템을 꺼내 보관 → 한 트랜잭션으로 auction_listing 삽입 + 등록비
 *       AUCTION_LIST_FEE + fish_ledger ACTIVE → IN_AUCTION. active_uid 유니크가 같은 고유 아이템의 두 번째 등록을
 *       막는다. 실패하면 아이템을 돌려준다.
 *   <li>구매: CurrencyService.atomic 한 트랜잭션: 구매자 −가격(AUCTION_BUY) + 판매자 +가격(AUCTION_SALE)
 *       −수수료(AUCTION_FEE) + 경매 행 FOR UPDATE 확인·SOLD + auction_sale + fish_ledger IN_AUCTION →
 *       ACTIVE + 낙찰품 우편 삽입. 의심 점수가 높으면 판매자 몫은 원장 대신 HELD 우편 골드로 들어간다. 커밋 뒤 구매자가 접속 중이고 칸이 있으면 바로
 *       수령한다.
 *   <li>만료·취소: 경매 행 EXPIRED/CANCELLED + 판매자 우편 반환 + 장부 ACTIVE를 한 트랜잭션으로. 등록비는 돌려주지 않는다.
 * </ul>
 *
 * 판매 수수료율은 등록할 때 판매자의 보정(경제 계열 2차 4%, 3차 3%)으로 정해 행에 저장한다 (판매자가 오프라인이어도 같은 비율).
 */
public final class AuctionHouse implements AuctionService {

    /** 판매 알림 (harbor:auction, 추가 제안). 판매자가 있는 서버가 보여준다. */
    public record SoldNotice(UUID seller, String itemName, long price, long net, boolean held) {}

    /** 경매 행. itemData는 직렬화 바이트의 Base64. */
    record Row(
            long id,
            UUID seller,
            String sellerName,
            @Nullable UUID itemUuid,
            String itemId,
            String itemName,
            String category,
            String grade,
            boolean trapSrc,
            String itemData,
            int amount,
            long price,
            long refPrice,
            double saleFeeRate,
            Instant expiresAt) {

        ItemStack item() {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(itemData));
        }

        AuctionListingInfo info() {
            return new AuctionListingInfo(
                    id, seller, sellerName, itemId, itemName, category, amount, price, expiresAt);
        }
    }

    /** 등록 후보: 손에 든 아이템의 기준가·분류. */
    private record Candidate(
            String itemId,
            String name,
            String category,
            Grade grade,
            long unitRef,
            @Nullable UUID uid,
            boolean trap) {}

    private record CachedPage(Instant at, List<Row> rows) {}

    /** 분류 (메뉴 아래 줄 버튼 순서). */
    static final List<String> CATEGORIES =
            List.of("all", "fish", "gear", "bait", "material", "food");

    static final Channel<SoldNotice> SOLD = new Channel<>("harbor:auction", SoldNotice.class);

    private static final StatKey LIST_FEE = StatKey.of("auction_list_fee");
    private static final StatKey SALE_FEE = StatKey.of("auction_fee");
    private static final StatKey SLOTS = StatKey.of("auction_slots");
    private static final int PAGE = 45;
    private static final String COLUMNS =
            "id, seller, seller_name, item_uuid, item_id, item_name, category, grade, trap_src, item_data,"
                    + " amount, price, ref_price, sale_fee_rate, expires_at";
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final MarketEngine market;
    private final MailManager mail;
    private final TradeGuard guard;
    private final FraudMonitor fraud;
    private final StationManager stations;
    private final Map<String, CachedPage> cache = new ConcurrentHashMap<>();

    public AuctionHouse(
            HarborApi api,
            Tasks tasks,
            Logger log,
            MarketEngine market,
            MailManager mail,
            TradeGuard guard,
            FraudMonitor fraud,
            StationManager stations) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.market = market;
        this.mail = mail;
        this.guard = guard;
        this.fraud = fraud;
        this.stations = stations;
    }

    public void start() {
        api.bus()
                .subscribe(
                        SOLD,
                        envelope ->
                                tasks.main()
                                        .execute(
                                                () -> {
                                                    SoldNotice n = envelope.payload();
                                                    Player seller = Bukkit.getPlayer(n.seller());
                                                    if (seller != null) {
                                                        api.messages()
                                                                .send(
                                                                        seller,
                                                                        n.held()
                                                                                ? "economy.auction.sold_held"
                                                                                : "economy.auction.sold",
                                                                        Placeholder.unparsed(
                                                                                "name",
                                                                                n.itemName()),
                                                                        Placeholder.unparsed(
                                                                                "price",
                                                                                String.valueOf(
                                                                                        n.price())),
                                                                        Placeholder.unparsed(
                                                                                "net",
                                                                                String.valueOf(
                                                                                        n.net())));
                                                    }
                                                }));
        // 1분마다 만료 처리 (잠금을 얻은 서버 하나만)
        var _ =
                tasks.timer(
                        20 * 30,
                        20 * 60,
                        () -> {
                            var _ = tasks.async(this::expireDue);
                        });
    }

    EconomyBalance balance() {
        return market.balance();
    }

    static String formatTime(HarborApi api, Instant instant) {
        return TIME.format(instant.atZone(api.time().zone()));
    }

    private static String ref(long listingId) {
        return "auction:" + listingId;
    }

    // ------------------------------------------------------------------ 등록

    /** 경매 게시판 반경 안인지. 콘텐츠에 게시판이 하나도 없으면 어디서나 (개발 서버). */
    boolean nearBoard(Player player) {
        if (!stations.anyOf(StationDef.Kind.AUCTION)) {
            return true;
        }
        return stations.nearest(player, StationDef.Kind.AUCTION, balance().auctionBoardRadius())
                .isPresent();
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

    /** /경매 등록 <가격>: 손에 든 아이템을 묶음째 등록한다 (확인 화면). */
    public void startListing(Player player, long price) {
        UUID uuid = player.getUniqueId();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            api.messages().send(player, "economy.auction.hold_item");
            return;
        }
        if (!nearBoard(player)) {
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
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("economy.auction.list_title"),
                        List.of(
                                api.messages()
                                        .get(
                                                "economy.auction.list_body",
                                                Placeholder.unparsed("name", cand.name()),
                                                Placeholder.unparsed(
                                                        "amount", String.valueOf(amount)),
                                                Placeholder.unparsed(
                                                        "price", String.valueOf(price)),
                                                Placeholder.unparsed("fee", String.valueOf(fee)),
                                                Placeholder.unparsed(
                                                        "sale_fee", String.valueOf(settle.fee())),
                                                Placeholder.unparsed(
                                                        "net", String.valueOf(settle.sellerNet())),
                                                Placeholder.unparsed(
                                                        "hours",
                                                        String.valueOf(b.auctionDurationHours())))),
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
        // 보관: 인벤토리에서 꺼낸다
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
                                    cache.clear();
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

    // ------------------------------------------------------------------ 조회

    private static Row row(ResultSet rs) throws SQLException {
        String uid = rs.getString(4);
        return new Row(
                rs.getLong(1),
                UUID.fromString(rs.getString(2)),
                rs.getString(3),
                uid == null ? null : UUID.fromString(uid),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getBoolean(9),
                Base64.getEncoder().encodeToString(rs.getBytes(10)),
                rs.getInt(11),
                rs.getLong(12),
                rs.getLong(13),
                rs.getDouble(14),
                rs.getTimestamp(15).toInstant());
    }

    /**
     * 진행 중인 등록 한 페이지 (가상 스레드에서 읽고 메인 스레드에서 완료). 분류별 첫 페이지는 30초 캐시.
     *
     * @param search 이름 검색어 (null이면 없음)
     * @param seller 판매자 한정 (null이면 전체)
     */
    CompletableFuture<List<Row>> page(
            String category, @Nullable String search, @Nullable UUID seller, int page) {
        boolean cacheable = search == null && seller == null && page == 0;
        if (cacheable) {
            CachedPage cached = cache.get(category);
            if (cached != null
                    && Duration.between(cached.at(), Instant.now()).toSeconds()
                            < balance().auctionCacheSeconds()) {
                return CompletableFuture.completedFuture(cached.rows());
            }
        }
        StringBuilder sql =
                new StringBuilder(
                        "SELECT " + COLUMNS + " FROM auction_listing WHERE state = 'ACTIVE'");
        List<String> args = new ArrayList<>();
        if (seller == null) {
            sql.append(" AND expires_at > CURRENT_TIMESTAMP(3)");
        } else {
            sql.append(" AND seller = ?");
            args.add(seller.toString());
        }
        if (!category.equals("all")) {
            sql.append(" AND category = ?");
            args.add(category);
        }
        if (search != null) {
            sql.append(" AND item_name LIKE ? ESCAPE '!'");
            args.add("%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        sql.append(" ORDER BY created_at DESC LIMIT ")
                .append(PAGE)
                .append(" OFFSET ")
                .append(page * PAGE);
        return api.database()
                .async(
                        c -> {
                            List<Row> rows = new ArrayList<>();
                            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                                for (int i = 0; i < args.size(); i++) {
                                    ps.setString(i + 1, args.get(i));
                                }
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        rows.add(row(rs));
                                    }
                                }
                            }
                            return List.copyOf(rows);
                        })
                .thenApplyAsync(
                        rows -> {
                            if (cacheable) {
                                cache.put(category, new CachedPage(Instant.now(), rows));
                            }
                            return rows;
                        },
                        tasks.main());
    }

    @Override
    public CompletableFuture<List<AuctionListingInfo>> active(
            @Nullable String category, int limit) {
        return page(category == null ? "all" : category, null, null, 0)
                .thenApply(rows -> rows.stream().limit(limit).map(Row::info).toList());
    }

    @Override
    public CompletableFuture<OptionalLong> lowestPrice(String itemId) {
        return api.database()
                .async(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT MIN(price) FROM auction_listing WHERE state = 'ACTIVE' AND item_id = ?"
                                                    + " AND expires_at > CURRENT_TIMESTAMP(3)")) {
                                ps.setString(1, itemId);
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (rs.next()) {
                                        long v = rs.getLong(1);
                                        return rs.wasNull()
                                                ? OptionalLong.empty()
                                                : OptionalLong.of(v);
                                    }
                                    return OptionalLong.empty();
                                }
                            }
                        })
                .thenApplyAsync(v -> v, tasks.main());
    }

    @Override
    public CompletableFuture<Integer> activeCount(UUID seller) {
        return api.database()
                .async(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT COUNT(*) FROM auction_listing WHERE seller = ? AND state = 'ACTIVE'")) {
                                ps.setString(1, seller.toString());
                                try (ResultSet rs = ps.executeQuery()) {
                                    return rs.next() ? rs.getInt(1) : 0;
                                }
                            }
                        })
                .thenApplyAsync(v -> v, tasks.main());
    }

    // ------------------------------------------------------------------ 화면

    /** /경매 [분류] · /경매 검색 · /경매 내목록. 메인 스레드. */
    public void open(
            Player player, String category, @Nullable String search, boolean mine, int page) {
        var _ =
                page(category, search, mine ? player.getUniqueId() : null, page)
                        .thenAccept(rows -> show(player, category, search, mine, page, rows))
                        .exceptionally(
                                e -> {
                                    log.warn("경매 목록을 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    private void show(
            Player player,
            String category,
            @Nullable String search,
            boolean mine,
            int page,
            List<Row> rows) {
        if (!player.isOnline()) {
            return;
        }
        Component title =
                api.messages()
                        .get(
                                mine ? "economy.auction.title_mine" : "economy.auction.title",
                                Placeholder.component(
                                        "category",
                                        api.messages().get("economy.auction.category." + category)),
                                Placeholder.unparsed("page", String.valueOf(page + 1)));
        Menu menu = new Menu(title, 6);
        for (int i = 0; i < rows.size() && i < PAGE; i++) {
            Row row = rows.get(i);
            menu.button(i, icon(row, mine), e -> clicked(player, row, mine));
        }
        int nav = 45;
        if (page > 0) {
            menu.button(
                    nav,
                    named(Material.ARROW, "economy.auction.previous"),
                    e -> open(player, category, search, mine, page - 1));
        }
        for (int i = 0; i < CATEGORIES.size(); i++) {
            String cat = CATEGORIES.get(i);
            ItemStack button =
                    named(
                            cat.equals(category) && !mine
                                    ? Material.LIME_STAINED_GLASS_PANE
                                    : Material.GRAY_STAINED_GLASS_PANE,
                            "economy.auction.category." + cat);
            menu.button(nav + 1 + i, button, e -> open(player, cat, null, false, 0));
        }
        menu.button(
                nav + 7,
                named(Material.CHEST, "economy.auction.my_listings"),
                e -> open(player, "all", null, true, 0));
        if (rows.size() >= PAGE) {
            menu.button(
                    nav + 8,
                    named(Material.ARROW, "economy.auction.next"),
                    e -> open(player, category, search, mine, page + 1));
        }
        menu.open(player);
    }

    private ItemStack icon(Row row, boolean mine) {
        ItemStack icon = row.item();
        List<Component> lore =
                new ArrayList<>(
                        Optional.ofNullable(icon.getData(DataComponentTypes.LORE))
                                .map(ItemLore::lines)
                                .orElse(List.of()));
        Duration left = Duration.between(Instant.now(), row.expiresAt());
        TagResolver[] tags = {
            Placeholder.unparsed("price", String.valueOf(row.price())),
            Placeholder.unparsed("seller", row.sellerName()),
            Placeholder.unparsed("hours", String.valueOf(Math.max(0, left.toHours()))),
            Placeholder.unparsed("minutes", String.valueOf(Math.max(0, left.toMinutesPart()))),
            Placeholder.unparsed("ref", String.valueOf(row.refPrice()))
        };
        lore.add(api.messages().get("economy.auction.lore.price", tags));
        lore.add(api.messages().get("economy.auction.lore.seller", tags));
        lore.add(
                api.messages()
                        .get(
                                left.isNegative()
                                        ? "economy.auction.lore.expired"
                                        : "economy.auction.lore.left",
                                tags));
        lore.add(
                api.messages()
                        .get(
                                mine ? "economy.auction.lore.cancel" : "economy.auction.lore.buy",
                                tags));
        icon.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                        lore.stream()
                                .map(
                                        c ->
                                                c.decorationIfAbsent(
                                                        TextDecoration.ITALIC,
                                                        TextDecoration.State.FALSE))
                                .toList()));
        return icon;
    }

    private ItemStack named(Material material, String key) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, api.messages().get(key));
        return stack;
    }

    private void clicked(Player player, Row row, boolean mine) {
        player.closeInventory();
        TagResolver[] tags = {
            Placeholder.unparsed("name", row.itemName()),
            Placeholder.unparsed("amount", String.valueOf(row.amount())),
            Placeholder.unparsed("price", String.valueOf(row.price())),
            Placeholder.unparsed("seller", row.sellerName())
        };
        if (mine || row.seller().equals(player.getUniqueId())) {
            api.dialogs()
                    .confirm(
                            player,
                            api.messages().get("economy.auction.cancel_title"),
                            List.of(api.messages().get("economy.auction.cancel_body", tags)),
                            api.messages().get("economy.auction.yes"),
                            api.messages().get("economy.auction.no"),
                            ok -> {
                                if (ok) {
                                    cancel(player, row.id());
                                }
                            });
            return;
        }
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("economy.auction.buy_title"),
                        List.of(api.messages().get("economy.auction.buy_body", tags)),
                        api.messages().get("economy.auction.yes"),
                        api.messages().get("economy.auction.no"),
                        ok -> {
                            if (ok) {
                                buy(player, row);
                            }
                        });
    }

    // ------------------------------------------------------------------ 구매

    /** 구매 직전 정보 (가상 스레드에서 읽음). */
    private record Prepared(Row row, FraudMath.Verdict verdict) {}

    void buy(Player player, Row shown) {
        UUID buyer = player.getUniqueId();
        if (shown.seller().equals(buyer)) {
            api.messages().send(player, "economy.auction.own");
            return;
        }
        if (api.players().get(player).balance(Currency.GOLD) < shown.price()) {
            api.messages().send(player, "economy.auction.no_gold");
            return;
        }
        Optional<TradeGuard.Ticket> ticket = guard.begin(buyer);
        if (ticket.isEmpty()) {
            api.messages().send(player, "economy.busy");
            return;
        }
        EconomyBalance.FraudRules rules = balance().fraud();
        int season = api.infra().season().value();
        CompletableFuture<Optional<Prepared>> prepared =
                api.database()
                        .async(
                                c -> {
                                    Row row = null;
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "SELECT "
                                                            + COLUMNS
                                                            + " FROM auction_listing WHERE id = ? AND state = 'ACTIVE'")) {
                                        ps.setLong(1, shown.id());
                                        try (ResultSet rs = ps.executeQuery()) {
                                            if (rs.next()) {
                                                row = row(rs);
                                            }
                                        }
                                    }
                                    if (row == null || row.price() != shown.price()) {
                                        return Optional.<Prepared>empty();
                                    }
                                    FraudMath.Signals signals =
                                            FraudMonitor.signals(
                                                    c,
                                                    buyer,
                                                    row.seller(),
                                                    row.price(),
                                                    row.refPrice(),
                                                    row.trapSrc(),
                                                    rules);
                                    return Optional.of(
                                            new Prepared(row, FraudMath.evaluate(signals, rules)));
                                });
        CompletableFuture<Void> flow =
                prepared.thenComposeAsync(
                        found -> {
                            if (found.isEmpty()) {
                                if (player.isOnline()) {
                                    api.messages().send(player, "economy.auction.gone");
                                }
                                cache.clear();
                                return CompletableFuture.completedFuture(null);
                            }
                            return settle(player, found.get(), season, rules);
                        },
                        tasks.main());
        var _ =
                ticket.get()
                        .track(flow)
                        .exceptionally(
                                e -> {
                                    log.warn("경매 구매 실패 {} #{}", buyer, shown.id(), e);
                                    if (player.isOnline()) {
                                        api.messages().send(player, "economy.auction.buy_failed");
                                    }
                                    return null;
                                });
    }

    /** 메인 스레드: 낙찰 트랜잭션. */
    private CompletableFuture<Void> settle(
            Player player, Prepared prepared, int season, EconomyBalance.FraudRules rules) {
        Row row = prepared.row();
        FraudMath.Verdict verdict = prepared.verdict();
        UUID buyer = player.getUniqueId();
        UUID seller = row.seller();
        boolean hold = verdict.hold();
        AuctionMath.Settlement settle = AuctionMath.settle(row.price(), row.saleFeeRate());
        String ref = ref(row.id());
        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(
                new LedgerEntry(
                        buyer, Currency.GOLD, -settle.buyerPays(), LedgerReason.AUCTION_BUY, ref));
        if (!hold) {
            entries.add(
                    new LedgerEntry(
                            seller,
                            Currency.GOLD,
                            settle.sellerGross(),
                            LedgerReason.AUCTION_SALE,
                            ref));
            if (settle.fee() > 0) {
                entries.add(
                        new LedgerEntry(
                                seller,
                                Currency.GOLD,
                                -settle.fee(),
                                LedgerReason.AUCTION_FEE,
                                ref));
            }
        }
        byte[] data = Base64.getDecoder().decode(row.itemData());
        Instant holdUntil =
                rules.autoRelease()
                        ? Instant.now().plus(Duration.ofHours(rules.holdHours()))
                        : null;
        UUID uid = row.itemUuid();
        SqlConsumer extra =
                c -> {
                    lockActive(c, row.id(), row.price());
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO auction_sale (listing_id, buyer, seller, price, fee, held) VALUES (?, ?, ?, ?, ?, ?)")) {
                        ps.setLong(1, row.id());
                        ps.setString(2, buyer.toString());
                        ps.setString(3, seller.toString());
                        ps.setLong(4, row.price());
                        ps.setLong(5, settle.fee());
                        ps.setBoolean(6, hold);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "UPDATE auction_listing SET state = 'SOLD', ended_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND state = 'ACTIVE'")) {
                        ps.setLong(1, row.id());
                        if (ps.executeUpdate() != 1) {
                            throw new LedgerException(LedgerError.REJECTED, "이미 끝난 경매");
                        }
                    }
                    if (uid != null
                            && !FishLedger.transition(
                                    c, uid, FishLedger.State.IN_AUCTION, FishLedger.State.ACTIVE)) {
                        throw new LedgerException(
                                LedgerError.REJECTED, "장부 상태가 IN_AUCTION이 아님: " + uid);
                    }
                    MailManager.insert(
                            c,
                            buyer,
                            season,
                            "item",
                            data,
                            0,
                            MailManager.SOURCE_AUCTION_WON,
                            ref,
                            false,
                            null);
                    if (hold) {
                        MailManager.insert(
                                c,
                                seller,
                                season,
                                "gold",
                                null,
                                settle.sellerNet(),
                                MailManager.SOURCE_AUCTION_HOLD,
                                ref,
                                true,
                                holdUntil);
                    }
                    if (verdict.flag()) {
                        FraudMonitor.record(
                                c,
                                verdict,
                                buyer,
                                seller,
                                ref,
                                String.join(",", verdict.reasons())
                                        + " price="
                                        + row.price()
                                        + " ref="
                                        + row.refPrice(),
                                hold);
                    }
                };
        return Tx.run(api, tasks, entries, extra)
                .thenAccept(
                        result -> {
                            cache.clear();
                            LedgerError error = Tx.error(result);
                            if (error != null) {
                                if (player.isOnline()) {
                                    api.messages()
                                            .send(
                                                    player,
                                                    switch (error) {
                                                        case INSUFFICIENT_FUNDS ->
                                                                "economy.auction.no_gold";
                                                        case REJECTED, DUPLICATE ->
                                                                "economy.auction.gone";
                                                        default -> "economy.auction.buy_failed";
                                                    });
                                }
                                return;
                            }
                            log.info(
                                    "경매 낙찰 #{} {} → {} {}G (수수료 {}G{})",
                                    row.id(),
                                    row.sellerName(),
                                    player.getName(),
                                    row.price(),
                                    settle.fee(),
                                    hold ? ", 대금 보류" : "");
                            mail.notifyArrived(buyer);
                            if (hold) {
                                mail.notifyArrived(seller);
                            }
                            api.bus()
                                    .publish(
                                            SOLD,
                                            new SoldNotice(
                                                    seller,
                                                    row.itemName(),
                                                    row.price(),
                                                    settle.sellerNet(),
                                                    hold));
                            Bukkit.getPluginManager()
                                    .callEvent(
                                            new AuctionSoldEvent(
                                                    buyer,
                                                    seller,
                                                    row.id(),
                                                    row.itemId(),
                                                    row.price(),
                                                    settle.fee(),
                                                    hold));
                            if (verdict.flag()) {
                                fraud.announce(buyer, seller, ref, verdict, hold);
                            }
                            if (player.isOnline()) {
                                api.messages()
                                        .send(
                                                player,
                                                "economy.auction.bought",
                                                Placeholder.unparsed("name", row.itemName()),
                                                Placeholder.unparsed(
                                                        "price", String.valueOf(row.price())));
                                mail.autoClaim(player, MailManager.SOURCE_AUCTION_WON, ref);
                            }
                        });
    }

    /** 같은 트랜잭션: 경매 행을 잠그고 ACTIVE·가격·기한을 확인한다. */
    private static void lockActive(Connection c, long id, long price) throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT state, price, expires_at FROM auction_listing WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()
                        || !rs.getString(1).equals("ACTIVE")
                        || rs.getLong(2) != price
                        || !rs.getTimestamp(3).toInstant().isAfter(Instant.now())) {
                    throw new LedgerException(LedgerError.REJECTED, "진행 중인 경매가 아님: " + id);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 취소·만료

    /**
     * 같은 트랜잭션: 경매를 끝내고 아이템을 판매자 우편함으로 돌려보낸다.
     *
     * @param seller 판매자 본인 취소면 판매자 (다르면 거부), 관리자·만료면 null
     * @return 판매자 (이미 끝난 경매면 빈 값)
     */
    private static Optional<UUID> returnToSeller(
            Connection c,
            long id,
            @Nullable UUID seller,
            String state,
            int season,
            boolean onlyExpired)
            throws SQLException {
        UUID owner;
        String uid;
        byte[] data;
        try (PreparedStatement ps =
                c.prepareStatement(
                        "SELECT seller, item_uuid, item_data, expires_at FROM auction_listing WHERE id = ? AND state = 'ACTIVE' FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                owner = UUID.fromString(rs.getString(1));
                uid = rs.getString(2);
                data = rs.getBytes(3);
                if (onlyExpired && rs.getTimestamp(4).toInstant().isAfter(Instant.now())) {
                    return Optional.empty();
                }
            }
        }
        if (seller != null && !seller.equals(owner)) {
            return Optional.empty();
        }
        try (PreparedStatement ps =
                c.prepareStatement(
                        "UPDATE auction_listing SET state = ?, ended_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND state = 'ACTIVE'")) {
            ps.setString(1, state);
            ps.setLong(2, id);
            if (ps.executeUpdate() != 1) {
                return Optional.empty();
            }
        }
        if (uid != null) {
            var _ =
                    FishLedger.transition(
                            c,
                            UUID.fromString(uid),
                            FishLedger.State.IN_AUCTION,
                            FishLedger.State.ACTIVE);
        }
        MailManager.insert(
                c,
                owner,
                season,
                "item",
                data,
                0,
                MailManager.SOURCE_AUCTION_RETURN,
                ref(id) + ":return",
                false,
                null);
        return Optional.of(owner);
    }

    /** 판매자 본인 취소 (게시판 반경 안, 등록비 환불 없음). */
    void cancel(Player player, long id) {
        UUID uuid = player.getUniqueId();
        if (!nearBoard(player)) {
            api.messages().send(player, "economy.auction.board_only");
            return;
        }
        Optional<TradeGuard.Ticket> ticket = guard.begin(uuid);
        if (ticket.isEmpty()) {
            api.messages().send(player, "economy.busy");
            return;
        }
        int season = api.infra().season().value();
        var _ =
                ticket.get()
                        .track(
                                api.database()
                                        .async(
                                                c ->
                                                        returnToSeller(
                                                                c,
                                                                id,
                                                                uuid,
                                                                "CANCELLED",
                                                                season,
                                                                false)))
                        .thenAcceptAsync(
                                owner -> {
                                    cache.clear();
                                    if (owner.isPresent()) {
                                        log.info("경매 취소 #{} {}", id, player.getName());
                                        if (player.isOnline()) {
                                            api.messages()
                                                    .send(player, "economy.auction.cancelled");
                                            mail.autoClaim(
                                                    player,
                                                    MailManager.SOURCE_AUCTION_RETURN,
                                                    ref(id) + ":return");
                                        }
                                    } else if (player.isOnline()) {
                                        api.messages().send(player, "economy.auction.gone");
                                    }
                                },
                                tasks.main())
                        .exceptionally(
                                e -> {
                                    log.warn("경매 취소 실패 #{}", id, e);
                                    return null;
                                });
    }

    /** 관리자 강제 취소: 아이템은 판매자 우편함. */
    public CompletableFuture<Boolean> adminCancel(long id) {
        int season = api.infra().season().value();
        return api.database()
                .async(c -> returnToSeller(c, id, null, "CANCELLED", season, false))
                .thenApplyAsync(
                        owner -> {
                            cache.clear();
                            owner.ifPresent(mail::notifyArrived);
                            return owner.isPresent();
                        },
                        tasks.main());
    }

    /** 가상 스레드: 기한이 지난 경매를 EXPIRED로 바꾸고 판매자에게 돌려보낸다 (잠금을 얻은 서버 하나만). */
    void expireDue() {
        Optional<wiki.creeper.harbor.common.redis.DistributedLock.Handle> lock;
        try {
            lock = api.locks().tryAcquire("harbor:lock:auction-expire", Duration.ofSeconds(50));
        } catch (RuntimeException e) {
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        int season = api.infra().season().value();
        try {
            List<Long> due =
                    api.database()
                            .tx(
                                    c -> {
                                        List<Long> ids = new ArrayList<>();
                                        try (PreparedStatement ps =
                                                c.prepareStatement(
                                                        "SELECT id FROM auction_listing WHERE state = 'ACTIVE'"
                                                                + " AND expires_at <= CURRENT_TIMESTAMP(3) ORDER BY expires_at LIMIT 200")) {
                                            try (ResultSet rs = ps.executeQuery()) {
                                                while (rs.next()) {
                                                    ids.add(rs.getLong(1));
                                                }
                                            }
                                        }
                                        return ids;
                                    });
            for (long id : due) {
                try {
                    Optional<UUID> owner =
                            api.database()
                                    .tx(c -> returnToSeller(c, id, null, "EXPIRED", season, true));
                    owner.ifPresent(mail::notifyArrived);
                } catch (SQLException e) {
                    log.warn("경매 만료 처리 실패 #{}: {}", id, String.valueOf(e.getMessage()));
                }
            }
            if (!due.isEmpty()) {
                cache.clear();
                log.info("경매 {}건 만료 (판매자 우편함으로 반환)", due.size());
            }
        } catch (SQLException e) {
            log.warn("경매 만료 조회 실패: {}", String.valueOf(e.getMessage()));
        } finally {
            lock.get().release();
        }
    }

    /** 관리자: 그 플레이어(이름)의 진행 중 경매. */
    CompletableFuture<List<Row>> listingsOf(UUID seller) {
        return page("all", null, seller, 0);
    }

    static String describe(Row row) {
        return "#"
                + row.id()
                + " "
                + row.itemName()
                + " x"
                + row.amount()
                + " "
                + row.price()
                + "G";
    }
}
