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

/** 경매 행 조회 (목록 페이지·최저가·등록 수)와 분류별 첫 페이지 캐시. 쓰기 쪽은 끝날 때 {@link #invalidate()}로 캐시를 비운다. */
final class AuctionListings {

    private record CachedPage(Instant at, List<Row> rows) {}

    /**
     * 등록 전 가격 참고 (추가 제안). 모두 개당 값.
     *
     * @param lowestUnit 지금 걸린 것 중 최저가
     * @param averageUnit 최근 days일 낙찰 평균
     * @param sold 최근 days일 낙찰 수
     */
    record PriceInfo(OptionalLong lowestUnit, OptionalLong averageUnit, int sold, int days) {}

    /**
     * 내 거래 기록 한 줄 (추가 제안).
     *
     * @param sold true면 내가 판 것, false면 산 것
     * @param fee 판매 수수료 (판 것만)
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
     * 진행 중인 등록 한 페이지 (가상 스레드에서 읽고 메인 스레드에서 완료). 분류별 첫 페이지는 30초 캐시.
     *
     * @param search 이름 검색어 (null이면 없음)
     * @param seller 판매자 한정 (null이면 전체)
     */
    CompletableFuture<List<Row>> page(
            String category, @Nullable String search, @Nullable UUID seller, int page) {
        return page(category, search, seller, page, AuctionSort.LATEST, PAGE);
    }

    /**
     * 정렬·쪽 크기를 정한 목록. 캐시는 최신순 기본 쪽 크기 첫 페이지만.
     *
     * @param size 쪽 크기 (1~PAGE)
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

    /** 등록 전 참고 가격 (개당 최저가 · 최근 days일 낙찰 평균). 메인 스레드에서 완료. */
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
     * 내 최근 거래 (판 것 또는 산 것, 새 것부터). 메인 스레드에서 완료.
     *
     * @param sold true면 판 것 (seller), false면 산 것 (buyer)
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
