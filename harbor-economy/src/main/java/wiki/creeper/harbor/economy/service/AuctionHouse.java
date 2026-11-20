package wiki.creeper.harbor.economy.service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.economy.api.AuctionListingInfo;
import wiki.creeper.harbor.economy.api.AuctionService;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.economy.content.StationDef;

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
 *
 * <p>공개 진입점만 여기 두고, 등록은 {@link AuctionLister}, 조회·캐시는 {@link AuctionListings}, 구매·낙찰은 {@link
 * AuctionSettlement}, 취소·만료는 {@link AuctionReturns}, 목록 화면은 {@link AuctionMenu}가 맡는다.
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

    /** 분류 (메뉴 아래 줄 버튼 순서). */
    static final List<String> CATEGORIES =
            List.of("all", "fish", "gear", "bait", "material", "food");

    static final Channel<SoldNotice> SOLD = new Channel<>("harbor:auction", SoldNotice.class);

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);

    private final HarborApi api;
    private final Tasks tasks;
    private final MarketEngine market;
    private final StationManager stations;
    private final AuctionListings listings;
    private final AuctionLister lister;
    private final AuctionSettlement settlement;
    private final AuctionReturns returns;
    private final AuctionMenu menu;

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
        this.market = market;
        this.stations = stations;
        this.listings = new AuctionListings(api, tasks, this);
        this.lister = new AuctionLister(api, tasks, log, market, mail, guard, this, listings);
        this.settlement =
                new AuctionSettlement(api, tasks, log, mail, guard, fraud, this, listings);
        this.returns = new AuctionReturns(api, tasks, log, mail, guard, this, listings);
        this.menu = new AuctionMenu(api, log, listings, settlement, returns);
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

    /** 경매의 원장·우편 참조 ID. */
    static String ref(long listingId) {
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

    /** /경매 등록 <가격>: 손에 든 아이템을 묶음째 등록한다 (확인 화면). */
    public void startListing(Player player, long price) {
        lister.startListing(player, price);
    }

    // ------------------------------------------------------------------ 조회

    /**
     * 진행 중인 등록 한 페이지 (가상 스레드에서 읽고 메인 스레드에서 완료). 분류별 첫 페이지는 30초 캐시.
     *
     * @param search 이름 검색어 (null이면 없음)
     * @param seller 판매자 한정 (null이면 전체)
     */
    CompletableFuture<List<Row>> page(
            String category, @Nullable String search, @Nullable UUID seller, int page) {
        return listings.page(category, search, seller, page);
    }

    @Override
    public CompletableFuture<List<AuctionListingInfo>> active(
            @Nullable String category, int limit) {
        return page(category == null ? "all" : category, null, null, 0)
                .thenApply(rows -> rows.stream().limit(limit).map(Row::info).toList());
    }

    @Override
    public CompletableFuture<OptionalLong> lowestPrice(String itemId) {
        return listings.lowestPrice(itemId);
    }

    @Override
    public CompletableFuture<Integer> activeCount(UUID seller) {
        return listings.activeCount(seller);
    }

    // ------------------------------------------------------------------ 화면

    /** /경매 [분류] · /경매 검색 · /경매 내목록. 메인 스레드. */
    public void open(
            Player player, String category, @Nullable String search, boolean mine, int page) {
        menu.open(player, category, search, mine, page);
    }

    // ------------------------------------------------------------------ 구매

    void buy(Player player, Row shown) {
        settlement.buy(player, shown);
    }

    // ------------------------------------------------------------------ 취소·만료

    /** 판매자 본인 취소 (게시판 반경 안, 등록비 환불 없음). */
    void cancel(Player player, long id) {
        returns.cancel(player, id);
    }

    /** 관리자 강제 취소: 아이템은 판매자 우편함. */
    public CompletableFuture<Boolean> adminCancel(long id) {
        return returns.adminCancel(id);
    }

    /** 가상 스레드: 기한이 지난 경매를 EXPIRED로 바꾸고 판매자에게 돌려보낸다 (잠금을 얻은 서버 하나만). */
    void expireDue() {
        returns.expireDue();
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
