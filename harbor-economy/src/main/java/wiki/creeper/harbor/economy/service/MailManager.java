package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MailService;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 우편함 (추가 제안, docs 20 7.4). 수령은 트랜잭션에서 state를 CLAIMED로 바꾼 뒤 메인 스레드에서 지급한다. 칸이 모자라면 수령하지 않고 남겨 둔다.
 * 다른 서버에 있는 수신자에게는 harbor:mail로 알린다.
 *
 * <ul>
 *   <li>중복 방지: (uuid, source, ref_id) 유니크. 같은 참조 ID로 다시 보내면 아무것도 하지 않고 성공으로 끝난다 (재시도 안전). ref_id가
 *       null이면 검사하지 않는다.
 *   <li>보류(HELD): 부정 거래 의심 판매 대금. hold_until이 지나면 자동으로 UNCLAIMED가 되고 (auto_release), hold_until이
 *       없으면 관리자가 풀 때까지 남는다.
 *   <li>경매 낙찰품·반환품은 경매 행 변경과 같은 트랜잭션에서 {@link #insert}로 넣어, 아이템이 한 순간에 한 곳에만 있게 한다.
 * </ul>
 */
public final class MailManager implements MailService {

    /** 우편 도착 알림 (harbor:mail, 추가 제안). */
    public record MailNotice(UUID player) {}

    /** 우편 한 통. itemData는 ItemStack 직렬화 바이트의 Base64 (메인 스레드에서 되살린다). */
    record Mail(
            long id,
            String kind,
            @Nullable String itemData,
            long gold,
            String source,
            Instant createdAt,
            boolean held,
            @Nullable Instant holdUntil) {

        @Nullable ItemStack item() {
            return itemData == null
                    ? null
                    : ItemStack.deserializeBytes(Base64.getDecoder().decode(itemData));
        }
    }

    static final Channel<MailNotice> CHANNEL = new Channel<>("harbor:mail", MailNotice.class);

    /** 경매·보류 출처 코드 (source 열). */
    public static final String SOURCE_AUCTION_WON = "auction_won";

    public static final String SOURCE_AUCTION_RETURN = "auction_return";
    public static final String SOURCE_AUCTION_HOLD = "auction_hold";

    private static final String SELECT =
            "SELECT id, kind, item_data, gold, source, created_at, state, hold_until FROM mailbox";

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final TradeGuard guard;

    private final MailMenu menu = new MailMenu(this);

    HarborApi api() {
        return api;
    }

    public MailManager(HarborApi api, Tasks tasks, Logger log, TradeGuard guard) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.guard = guard;
    }

    public void start() {
        api.bus()
                .subscribe(
                        CHANNEL,
                        envelope ->
                                tasks.main()
                                        .execute(
                                                () -> {
                                                    Player player =
                                                            Bukkit.getPlayer(
                                                                    envelope.payload().player());
                                                    if (player != null) {
                                                        api.messages()
                                                                .send(
                                                                        player,
                                                                        "economy.mail.arrived");
                                                    }
                                                }));
        // 1분마다 보류 기한이 지난 우편을 푼다 (여러 서버가 같이 돌아도 조건부 UPDATE라 안전)
        var _ =
                tasks.timer(
                        20 * 60,
                        20 * 60,
                        () -> {
                            var _ = tasks.async(this::releaseExpiredHolds);
                        });
    }

    // ------------------------------------------------------------------ 쓰기

    /**
     * 같은 트랜잭션 안에서 우편을 넣는다 (블로킹, 메인 스레드 금지). 같은 (수신자, 출처, 참조 ID)가 이미 있으면 넣지 않는다.
     *
     * @param holdUntil HELD로 넣을 때 자동 해제 시각 (null이면 관리자 해제)
     * @return 새로 넣었으면 true, 이미 있었으면 false
     */
    public static boolean insert(
            Connection c,
            UUID player,
            int season,
            String kind,
            byte @Nullable [] data,
            long gold,
            String source,
            @Nullable String refId,
            boolean held,
            @Nullable Instant holdUntil)
            throws SQLException {
        try (PreparedStatement ps =
                c.prepareStatement(
                        "INSERT INTO mailbox (uuid, season, kind, item_data, gold, source, ref_id, state, hold_until)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE id = id")) {
            ps.setString(1, player.toString());
            ps.setInt(2, season);
            ps.setString(3, kind);
            if (data == null) {
                ps.setNull(4, Types.BLOB);
            } else {
                ps.setBytes(4, data);
            }
            ps.setLong(5, gold);
            ps.setString(6, source);
            ps.setString(7, refId);
            ps.setString(8, held ? "HELD" : "UNCLAIMED");
            ps.setTimestamp(9, holdUntil == null ? null : Timestamp.from(holdUntil));
            // ON DUPLICATE KEY UPDATE로 아무것도 바뀌지 않으면 영향 행 수가 0
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public SqlConsumer sendItemInTx(UUID player, ItemStack item, String source, String refId) {
        byte[] data = item.serializeAsBytes();
        int season = api.infra().season().value();
        return c -> insert(c, player, season, "item", data, 0, source, refId, false, null);
    }

    /** 커밋 뒤 수신자에게 알린다 (다른 서버 포함). */
    @Override
    public void notifyArrived(UUID player) {
        api.bus().publish(CHANNEL, new MailNotice(player));
    }

    @Override
    public CompletableFuture<Void> sendItem(
            UUID player, ItemStack item, String source, @Nullable String refId) {
        byte[] data = item.serializeAsBytes();
        return send(player, "item", data, 0, source, refId);
    }

    @Override
    public CompletableFuture<Void> sendGold(
            UUID player, long gold, String source, @Nullable String refId) {
        if (gold <= 0) {
            throw new IllegalArgumentException("골드는 0보다 커야 합니다");
        }
        return send(player, "gold", null, gold, source, refId);
    }

    private CompletableFuture<Void> send(
            UUID player,
            String kind,
            byte @Nullable [] data,
            long gold,
            String source,
            @Nullable String refId) {
        int season = api.infra().season().value();
        return api.database()
                .async(c -> insert(c, player, season, kind, data, gold, source, refId, false, null))
                .thenAccept(
                        inserted -> {
                            if (inserted) {
                                notifyArrived(player);
                            } else {
                                log.debug("이미 보낸 우편이라 건너뜁니다: {} {} {}", player, source, refId);
                            }
                        });
    }

    /**
     * 커밋 뒤 아이템을 돌려준다. 접속 중이면 인벤토리(넘치면 우편함), 아니면 우편함. 메인 스레드.
     *
     * @param refId 우편으로 갈 때의 참조 ID (중복 방지)
     */
    public void giveOrMail(UUID player, List<ItemStack> items, String source, String refId) {
        Player online = Bukkit.getPlayer(player);
        int index = 0;
        for (ItemStack item : items) {
            String ref = refId + ":" + index++;
            // 서버 이동 저장본을 뜬 뒤면 인벤토리에 넣어도 저장되지 않으므로 우편으로
            if (online != null && online.isOnline() && !api.players().frozen(player)) {
                for (ItemStack left : online.getInventory().addItem(item.clone()).values()) {
                    var _ = sendItem(player, left, source, ref);
                    api.messages().send(online, "economy.shop.to_mail");
                }
            } else {
                var _ = sendItem(player, item, source, ref);
            }
        }
    }

    @Override
    public CompletableFuture<Integer> unclaimed(UUID player) {
        return api.database()
                .async(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT COUNT(*) FROM mailbox WHERE uuid = ? AND state = 'UNCLAIMED'")) {
                                ps.setString(1, player.toString());
                                try (ResultSet rs = ps.executeQuery()) {
                                    return rs.next() ? rs.getInt(1) : 0;
                                }
                            }
                        });
    }

    // ------------------------------------------------------------------ 보류

    /** 가상 스레드: 보류 기한이 지난 우편을 UNCLAIMED로 바꾸고 수신자에게 알린다. */
    void releaseExpiredHolds() {
        try {
            List<UUID> released =
                    api.database()
                            .tx(
                                    c -> {
                                        List<Long> ids = new ArrayList<>();
                                        List<UUID> owners = new ArrayList<>();
                                        try (PreparedStatement ps =
                                                c.prepareStatement(
                                                        "SELECT id, uuid FROM mailbox WHERE state = 'HELD'"
                                                                + " AND hold_until IS NOT NULL AND hold_until <= CURRENT_TIMESTAMP(3)"
                                                                + " LIMIT 200 FOR UPDATE")) {
                                            try (ResultSet rs = ps.executeQuery()) {
                                                while (rs.next()) {
                                                    ids.add(rs.getLong(1));
                                                    owners.add(UUID.fromString(rs.getString(2)));
                                                }
                                            }
                                        }
                                        try (PreparedStatement ps =
                                                c.prepareStatement(
                                                        "UPDATE mailbox SET state = 'UNCLAIMED', hold_until = NULL WHERE id = ? AND state = 'HELD'")) {
                                            for (long id : ids) {
                                                ps.setLong(1, id);
                                                ps.addBatch();
                                            }
                                            ps.executeBatch();
                                        }
                                        return owners;
                                    });
            released.stream().distinct().forEach(this::notifyArrived);
        } catch (SQLException e) {
            log.warn("보류 우편 해제 실패: {}", String.valueOf(e.getMessage()));
        }
    }

    /** 관리자: 보류 우편을 지금 푼다 (참조 ID = auction:<경매 ID>). 풀린 우편 수. */
    public CompletableFuture<Integer> releaseHold(String refId) {
        return api.database()
                .async(
                        c -> {
                            List<UUID> owners = new ArrayList<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "SELECT uuid FROM mailbox WHERE source = ? AND ref_id = ? AND state = 'HELD' FOR UPDATE")) {
                                ps.setString(1, SOURCE_AUCTION_HOLD);
                                ps.setString(2, refId);
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        owners.add(UUID.fromString(rs.getString(1)));
                                    }
                                }
                            }
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "UPDATE mailbox SET state = 'UNCLAIMED', hold_until = NULL"
                                                    + " WHERE source = ? AND ref_id = ? AND state = 'HELD'")) {
                                ps.setString(1, SOURCE_AUCTION_HOLD);
                                ps.setString(2, refId);
                                ps.executeUpdate();
                            }
                            return owners;
                        })
                .thenApply(
                        owners -> {
                            owners.forEach(this::notifyArrived);
                            return owners.size();
                        });
    }

    // ------------------------------------------------------------------ 화면

    private static Mail read(ResultSet rs) throws SQLException {
        byte[] data = rs.getBytes(3);
        Timestamp hold = rs.getTimestamp(8);
        return new Mail(
                rs.getLong(1),
                rs.getString(2),
                data == null ? null : Base64.getEncoder().encodeToString(data),
                rs.getLong(4),
                rs.getString(5),
                rs.getTimestamp(6).toInstant(),
                "HELD".equals(rs.getString(7)),
                hold == null ? null : hold.toInstant());
    }

    /** /우편함: 안 받은 우편(보류 중 포함) 목록. 누르면 수령, "모두 받기"는 {@link #claimAll}. */
    public void open(Player player) {
        var _ = load(player.getUniqueId()).thenAccept(mails -> menu.show(player, mails));
    }

    /** 안 받은 우편(보류 포함, 오래된 순, 270개까지). 메인 스레드에서 완료. */
    private CompletableFuture<List<Mail>> load(UUID uuid) {
        return api.database()
                .async(
                        c -> {
                            List<Mail> mails = new ArrayList<>();
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            SELECT
                                                    + " WHERE uuid = ? AND state IN ('UNCLAIMED', 'HELD') ORDER BY id LIMIT 270")) {
                                ps.setString(1, uuid.toString());
                                try (ResultSet rs = ps.executeQuery()) {
                                    while (rs.next()) {
                                        mails.add(read(rs));
                                    }
                                }
                            }
                            return mails;
                        })
                .thenApplyAsync(m -> m, tasks.main());
    }

    /**
     * 모두 받기 (추가 제안): 보류가 아닌 우편을 오래된 순으로 하나씩 받는다 (같은 CLAIMED 트랜잭션). 아이템은 빈 칸이 있을 때만 받고, 칸이 모자라면 남겨
     * 둔다. 끝나면 받은 수·남은 수를 알리고 우편함을 다시 연다. 메인 스레드.
     */
    public void claimAll(Player player) {
        var _ =
                load(player.getUniqueId())
                        .thenAccept(mails -> claimNext(player, mails.iterator(), 0, 0));
    }

    private void claimNext(Player player, java.util.Iterator<Mail> it, int got, int left) {
        if (!player.isOnline()) {
            return;
        }
        while (it.hasNext()) {
            Mail mail = it.next();
            if (mail.held() || (mail.item() != null && player.getInventory().firstEmpty() < 0)) {
                left++;
                continue;
            }
            int g = got;
            int l = left;
            var _ =
                    claim(player, mail, false, false)
                            .thenAccept(
                                    ok -> claimNext(player, it, ok ? g + 1 : g, ok ? l : l + 1));
            return;
        }
        api.messages()
                .send(
                        player,
                        left > 0 ? "economy.mail.claim_all_partial" : "economy.mail.claim_all_done",
                        Placeholder.unparsed("count", String.valueOf(got)),
                        Placeholder.unparsed("left", String.valueOf(left)));
        open(player);
    }

    /** 화면 아이콘 (MailMenu). */
    ItemStack icon(Mail mail) {
        ItemStack item = mail.item();
        ItemStack icon;
        if (item != null) {
            icon = item.clone();
        } else {
            icon = ItemStack.of(Material.GOLD_NUGGET);
            icon.setData(
                    DataComponentTypes.ITEM_NAME,
                    api.messages()
                            .get(
                                    "economy.mail.gold",
                                    Placeholder.unparsed("gold", String.valueOf(mail.gold()))));
        }
        List<Component> lore =
                new ArrayList<>(
                        Optional.ofNullable(icon.getData(DataComponentTypes.LORE))
                                .map(ItemLore::lines)
                                .orElse(List.of()));
        String sourceKey = "economy.mail.source." + mail.source();
        lore.add(
                api.messages()
                        .get(
                                api.messages().has(sourceKey) ? sourceKey : "economy.mail.from",
                                Placeholder.unparsed("source", mail.source())));
        if (mail.held()) {
            Instant until = mail.holdUntil();
            lore.add(
                    until == null
                            ? api.messages().get("economy.mail.held_manual")
                            : api.messages()
                                    .get(
                                            "economy.mail.held_until",
                                            Placeholder.unparsed(
                                                    "time", AuctionHouse.formatTime(api, until))));
        }
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

    // ------------------------------------------------------------------ 수령

    /** 경매 낙찰 직후 자동 수령: 구매자가 접속 중이고 칸이 있으면 바로 받는다. 칸이 없으면 우편함에 남는다. 메인 스레드. */
    public void autoClaim(Player player, String source, String refId) {
        UUID uuid = player.getUniqueId();
        var _ =
                api.database()
                        .async(
                                c -> {
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    SELECT
                                                            + " WHERE uuid = ? AND source = ? AND ref_id = ? AND state = 'UNCLAIMED'")) {
                                        ps.setString(1, uuid.toString());
                                        ps.setString(2, source);
                                        ps.setString(3, refId);
                                        try (ResultSet rs = ps.executeQuery()) {
                                            return rs.next()
                                                    ? Optional.of(read(rs))
                                                    : Optional.<Mail>empty();
                                        }
                                    }
                                })
                        .thenAcceptAsync(
                                mail -> {
                                    if (mail.isEmpty() || !player.isOnline()) {
                                        return;
                                    }
                                    ItemStack item = mail.get().item();
                                    if (item != null && player.getInventory().firstEmpty() < 0) {
                                        api.messages().send(player, "economy.mail.arrived");
                                        return;
                                    }
                                    var _ = claim(player, mail.get(), false, true);
                                },
                                tasks.main());
    }

    /**
     * 우편 하나 수령. 결과(받았는지)는 메인 스레드에서 완료된다.
     *
     * @param reopen 끝나면 우편함을 다시 연다
     * @param notify 결과 문구를 보낸다 (모두 받기는 끝에 한 번만 알린다)
     */
    CompletableFuture<Boolean> claim(Player player, Mail mail, boolean reopen, boolean notify) {
        UUID uuid = player.getUniqueId();
        if (mail.held()) {
            api.messages().send(player, "economy.mail.held");
            return CompletableFuture.completedFuture(false);
        }
        ItemStack item = mail.item();
        if (item != null && player.getInventory().firstEmpty() < 0) {
            api.messages().send(player, "economy.mail.full");
            return CompletableFuture.completedFuture(false);
        }
        Optional<TradeGuard.Ticket> ticket = guard.begin(uuid);
        if (ticket.isEmpty()) {
            api.messages().send(player, "economy.busy");
            return CompletableFuture.completedFuture(false);
        }
        String update =
                "UPDATE mailbox SET state = 'CLAIMED', claimed_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND state = 'UNCLAIMED'";
        CompletableFuture<Boolean> claimed;
        if (mail.kind().equals("gold")) {
            claimed =
                    api.currency()
                            .atomic(
                                    List.of(
                                            new LedgerEntry(
                                                    uuid,
                                                    Currency.GOLD,
                                                    mail.gold(),
                                                    LedgerReason.MAIL_GOLD,
                                                    "mail:" + mail.id())),
                                    c -> {
                                        try (PreparedStatement ps = c.prepareStatement(update)) {
                                            ps.setLong(1, mail.id());
                                            if (ps.executeUpdate() != 1) {
                                                throw new LedgerException(
                                                        LedgerError.REJECTED,
                                                        "이미 받은 우편: " + mail.id());
                                            }
                                        }
                                    })
                            .thenApply(r -> r.isOk());
        } else {
            claimed =
                    api.database()
                            .async(
                                    c -> {
                                        try (PreparedStatement ps = c.prepareStatement(update)) {
                                            ps.setLong(1, mail.id());
                                            return ps.executeUpdate() == 1;
                                        }
                                    })
                            .thenApplyAsync(
                                    ok -> {
                                        if (ok && item != null) {
                                            if (player.isOnline()) {
                                                player.getInventory()
                                                        .addItem(item.clone())
                                                        .values()
                                                        .forEach(
                                                                left ->
                                                                        player.getWorld()
                                                                                .dropItem(
                                                                                        Objects
                                                                                                .requireNonNull(
                                                                                                        player
                                                                                                                .getLocation()),
                                                                                        left));
                                            } else {
                                                // 커밋 사이에 나갔으면 우편함으로 되돌린다
                                                var _ =
                                                        sendItem(
                                                                uuid,
                                                                item,
                                                                "mail_return",
                                                                "mail:" + mail.id());
                                            }
                                        }
                                        return ok;
                                    },
                                    tasks.main());
        }
        return ticket.get()
                .track(claimed)
                .exceptionally(
                        e -> {
                            log.warn("우편 수령 실패: {} {}", uuid, mail.id(), e);
                            return false;
                        })
                .thenApplyAsync(
                        ok -> {
                            boolean done = Boolean.TRUE.equals(ok);
                            if (!player.isOnline()) {
                                return done;
                            }
                            if (notify) {
                                api.messages()
                                        .send(
                                                player,
                                                done
                                                        ? "economy.mail.claimed"
                                                        : "economy.mail.failed");
                            }
                            if (reopen) {
                                open(player);
                            }
                            return done;
                        },
                        tasks.main());
    }
}
