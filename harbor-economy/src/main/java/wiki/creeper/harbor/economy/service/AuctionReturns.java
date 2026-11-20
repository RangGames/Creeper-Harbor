package wiki.creeper.harbor.economy.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 경매 취소·만료 (docs 20 7.4 만료). 경매 행 EXPIRED/CANCELLED + 판매자 우편 반환 + 장부 ACTIVE를 한 트랜잭션으로 처리한다. 등록비는
 * 돌려주지 않는다.
 */
final class AuctionReturns {

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final MailManager mail;
    private final TradeGuard guard;
    private final AuctionHouse house;
    private final AuctionListings listings;

    AuctionReturns(
            HarborApi api,
            Tasks tasks,
            Logger log,
            MailManager mail,
            TradeGuard guard,
            AuctionHouse house,
            AuctionListings listings) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.mail = mail;
        this.guard = guard;
        this.house = house;
        this.listings = listings;
    }

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
                AuctionHouse.ref(id) + ":return",
                false,
                null);
        return Optional.of(owner);
    }

    /** 판매자 본인 취소 (게시판 반경 안, 등록비 환불 없음). */
    void cancel(Player player, long id) {
        UUID uuid = player.getUniqueId();
        if (!house.nearBoard(player)) {
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
                                    listings.invalidate();
                                    if (owner.isPresent()) {
                                        log.info("경매 취소 #{} {}", id, player.getName());
                                        if (player.isOnline()) {
                                            api.messages()
                                                    .send(player, "economy.auction.cancelled");
                                            mail.autoClaim(
                                                    player,
                                                    MailManager.SOURCE_AUCTION_RETURN,
                                                    AuctionHouse.ref(id) + ":return");
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
    CompletableFuture<Boolean> adminCancel(long id) {
        int season = api.infra().season().value();
        return api.database()
                .async(c -> returnToSeller(c, id, null, "CANCELLED", season, false))
                .thenApplyAsync(
                        owner -> {
                            listings.invalidate();
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
                listings.invalidate();
                log.info("경매 {}건 만료 (판매자 우편함으로 반환)", due.size());
            }
        } catch (SQLException e) {
            log.warn("경매 만료 조회 실패: {}", String.valueOf(e.getMessage()));
        } finally {
            lock.get().release();
        }
    }
}
