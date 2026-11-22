package wiki.creeper.harbor.economy.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.economy.service.AuctionHouse.Row;

/**
 * Auction row queries (list pages, lowest price, listing count) and the first-page cache per
 * category. Writers clear the cache with {@link #invalidate()} when done.
 */
final class AuctionListings {

    private record CachedPage(Instant at, List<Row> rows) {}

    /**
     * Price reference before listing (proposed). All values are per unit.
     *
     * @param lowestUnit lowest price among current listings
     * @param averageUnit sale average over the last days days
     * @param sold number of sales over the last days days
     */
    record PriceInfo(OptionalLong lowestUnit, OptionalLong averageUnit, int sold, int days) {}

    /**
     * One line of my trade history (proposed).
     *
     * @param sold true if I sold it, false if I bought it
     * @param fee sale fee (sales only)
     */
    record HistoryRow(
            String itemName,
            int amount,
            long price,
            long fee,
            boolean held,
            Instant soldAt,
            boolean sold) {}

    static final int PAGE = 45;
    static final String COLUMNS =
            "id, seller, seller_name, item_uuid, item_id, item_name, category, grade, trap_src, item_data,"
                    + " amount, price, ref_price, sale_fee_rate, expires_at";

    private final HarborApi api;
    private final Tasks tasks;
    private final AuctionHouse house;
    private final Map<String, CachedPage> cache = new ConcurrentHashMap<>();

    AuctionListings(HarborApi api, Tasks tasks, AuctionHouse house) {
        this.api = api;
        this.tasks = tasks;
        this.house = house;
    }

    void invalidate() {
        cache.clear();
    }

    static Row row(ResultSet rs) throws SQLException {
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
     * One page of active listings (read on a virtual thread, completed on the main thread). The
     * first page per category is cached for 30 seconds.
     *
     * @param search name search term (null for none)
     * @param seller restrict to a seller (null for all)
     */
    CompletableFuture<List<Row>> page(
            String category, @Nullable String search, @Nullable UUID seller, int page) {
        return page(category, search, seller, page, AuctionSort.LATEST, PAGE);
    }

    /**
     * A list with a given sort and page size. Only the first page with newest-first order and the
     * default page size is cached.
     *
     * @param size page size (1 to PAGE)
     */
    CompletableFuture<List<Row>> page(
            String category,
            @Nullable String search,
            @Nullable UUID seller,
            int page,
            AuctionSort sort,
            int size) {
        int limit = Math.clamp(size, 1, PAGE);
        boolean cacheable =
                search == null
                        && seller == null
                        && page == 0
                        && sort == AuctionSort.LATEST
                        && limit == PAGE;
        if (cacheable) {
            CachedPage cached = cache.get(category);
            if (cached != null
                    && Duration.between(cached.at(), Instant.now()).toSeconds()
                            < house.balance().auctionCacheSeconds()) {
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
        sql.append(" ORDER BY ")
                .append(sort.orderBy())
                .append(" LIMIT ")
                .append(limit)
                .append(" OFFSET ")
                .append(Math.max(0, page) * limit);
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

    CompletableFuture<OptionalLong> lowestPrice(String itemId) {
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

    /**
     * Reference prices before listing (lowest unit price · sale average over the last days days).
     * Completes on the main thread.
     */
    CompletableFuture<PriceInfo> priceInfo(String itemId, int days) {
        int d = Math.clamp(days, 1, 90);
        return api.database()
                .async(
                        c -> {
                            OptionalLong lowest = OptionalLong.empty();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT MIN(FLOOR(price / amount)) FROM auction_listing"
                                                    + " WHERE state = 'ACTIVE' AND item_id = ? AND expires_at > CURRENT_TIMESTAMP(3)")) {
                                ps.setString(1, itemId);
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (rs.next()) {
                                        long v = rs.getLong(1);
                                        lowest =
                                                rs.wasNull()
                                                        ? OptionalLong.empty()
                                                        : OptionalLong.of(v);
                                    }
                                }
                            }
                            OptionalLong average = OptionalLong.empty();
                            int sold = 0;
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT AVG(s.price / l.amount), COUNT(*) FROM auction_sale s"
                                                    + " JOIN auction_listing l ON l.id = s.listing_id"
                                                    + " WHERE l.item_id = ? AND s.sold_at > CURRENT_TIMESTAMP(3) - INTERVAL ? DAY")) {
                                ps.setString(1, itemId);
                                ps.setInt(2, d);
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (rs.next()) {
                                        double v = rs.getDouble(1);
                                        boolean none = rs.wasNull();
                                        sold = rs.getInt(2);
                                        average =
                                                none || sold == 0
                                                        ? OptionalLong.empty()
                                                        : OptionalLong.of(Math.round(v));
                                    }
                                }
                            }
                            return new PriceInfo(lowest, average, sold, d);
                        })
                .thenApplyAsync(v -> v, tasks.main());
    }

    /**
     * My recent trades (sold or bought, newest first). Completes on the main thread.
     *
     * @param sold true for sold (seller), false for bought (buyer)
     */
    CompletableFuture<List<HistoryRow>> history(UUID player, boolean sold, int limit) {
        String who = sold ? "s.seller" : "s.buyer";
        String sql =
                "SELECT l.item_name, l.amount, s.price, s.fee, s.held, s.sold_at FROM auction_sale s"
                        + " JOIN auction_listing l ON l.id = s.listing_id WHERE "
                        + who
                        + " = ? ORDER BY s.sold_at DESC LIMIT ?";
        return api.database()
                .async(
                        c -> {
                            List<HistoryRow> rows = new ArrayList<>();
                            try (PreparedStatement ps = c.prepareStatement(sql)) {
                                ps.setString(1, player.toString());
                                ps.setInt(2, Math.clamp(limit, 1, PAGE));
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        rows.add(
                                                new HistoryRow(
                                                        rs.getString(1),
                                                        rs.getInt(2),
                                                        rs.getLong(3),
                                                        rs.getLong(4),
                                                        rs.getBoolean(5),
                                                        rs.getTimestamp(6).toInstant(),
                                                        sold));
                                    }
                                }
                            }
                            return List.copyOf(rows);
                        })
                .thenApplyAsync(v -> v, tasks.main());
    }

    CompletableFuture<Integer> activeCount(UUID seller) {
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
}
