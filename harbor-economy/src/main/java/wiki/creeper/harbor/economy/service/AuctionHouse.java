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
 * Auction house (docs 20 7.4 auction rules, listing, buying, expiry). An item is in exactly one
 * place at any moment (inventory → auction row → mailbox).
 *
 * <ul>
 *   <li>Listing: checks (slots, tradable, listing price range, new account) → take the item from
 *       the hand and hold it → in one transaction, insert auction_listing + listing fee
 *       AUCTION_LIST_FEE + fish_ledger ACTIVE → IN_AUCTION. The active_uid unique key blocks a
 *       second listing of the same unique item. On failure the item is returned.
 *   <li>Buying: one CurrencyService.atomic transaction: buyer −price (AUCTION_BUY) + seller +price
 *       (AUCTION_SALE) −fee (AUCTION_FEE) + auction row FOR UPDATE check and SOLD + auction_sale +
 *       fish_ledger IN_AUCTION → ACTIVE + insert the won item into mail. If the suspicion score is
 *       high, the seller's share goes into HELD mail gold instead of the ledger. After commit, if
 *       the buyer is online and has space, it is claimed immediately.
 *   <li>Expiry and cancellation: auction row EXPIRED/CANCELLED + return to the seller's mail +
 *       ledger ACTIVE in one transaction. The listing fee is not refunded.
 * </ul>
 *
 * The sale fee rate is set at listing time from the seller's modifiers (economy line tier 2 4%,
 * tier 3 3%) and stored in the row (same rate even if the seller is offline).
 *
 * <p>Only the public entry points live here; listing is handled by {@link AuctionLister}, queries
 * and caching by {@link AuctionListings}, buying and settlement by {@link AuctionSettlement},
 * cancellation and expiry by {@link AuctionReturns}, and the list screen by {@link AuctionMenu}.
 */
public final class AuctionHouse implements AuctionService {

    /** Sale notice (harbor:auction, proposed). Shown by the server the seller is on. */
    public record SoldNotice(UUID seller, String itemName, long price, long net, boolean held) {}

    /** Auction row. itemData is the serialized bytes in Base64. */
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

    /** Category (button order on the bottom row of the menu). */
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
        // Process expiry every minute (only the one server that acquires the lock)
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

    /** Ledger and mail reference ID for an auction. */
    static String ref(long listingId) {
        return "auction:" + listingId;
    }

    // ------------------------------------------------------------------ Listing

    /**
     * Whether within an auction board's radius. Anywhere if the content has no boards (dev server).
     */
    boolean nearBoard(Player player) {
        if (!stations.anyOf(StationDef.Kind.AUCTION)) {
            return true;
        }
        return stations.nearest(player, StationDef.Kind.AUCTION, balance().auctionBoardRadius())
                .isPresent();
    }

    /** /경매 등록 <price>: lists the held item as a whole stack (confirmation screen). */
    public void startListing(Player player, long price) {
        lister.startListing(player, price);
    }

    // ------------------------------------------------------------------ Queries

    /**
     * One page of active listings (read on a virtual thread, completed on the main thread). The
     * first page per category is cached for 30 seconds.
     *
     * @param search name search term (null for none)
     * @param seller restrict to a seller (null for all)
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

    // ------------------------------------------------------------------ Screens

    /** /경매 [category] · /경매 검색 · /경매 내목록. Main thread. */
    public void open(
            Player player, String category, @Nullable String search, boolean mine, int page) {
        menu.open(player, category, search, mine, page);
    }

    // ------------------------------------------------------------------ Buying

    void buy(Player player, Row shown) {
        settlement.buy(player, shown);
    }

    // ------------------------------------------------------------------ Cancellation and expiry

    /** Cancellation by the seller (within the board radius, listing fee not refunded). */
    void cancel(Player player, long id) {
        returns.cancel(player, id);
    }

    /** Admin forced cancellation: the item goes to the seller's mailbox. */
    public CompletableFuture<Boolean> adminCancel(long id) {
        return returns.adminCancel(id);
    }

    /**
     * Virtual thread: marks overdue auctions EXPIRED and returns them to the sellers (only the one
     * server that acquires the lock).
     */
    void expireDue() {
        returns.expireDue();
    }

    /** Admin: active auctions of that player (by name). */
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
