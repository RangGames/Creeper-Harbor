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
        boolean cacheable = search == null && seller == null && page == 0;
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
