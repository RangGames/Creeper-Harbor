package wiki.creeper.harbor.economy.service;

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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
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
 * Mailbox (proposed, docs 20 7.4). Claiming sets state to CLAIMED in a transaction, then pays out
 * on the main thread. If there is not enough room the mail is left unclaimed. Recipients on other
 * servers are notified via harbor:mail.
 *
 * <ul>
 *   <li>Deduplication: (uuid, source, ref_id) is unique. Sending again with the same reference ID
 *       does nothing and succeeds (safe to retry). Not checked when ref_id is null.
 *   <li>Held (HELD): sale proceeds suspected of fraud. Becomes UNCLAIMED automatically once
 *       hold_until passes (auto_release); without hold_until it stays until an admin releases it.
 *   <li>Won and returned auction items are inserted with {@link #insert} in the same transaction as
 *       the auction row change, so an item is only ever in one place at a time.
 * </ul>
 */
public final class MailManager implements MailService {

    /** Mail arrival notice (harbor:mail, proposed). */
    public record MailNotice(UUID player) {}

    /**
     * One mail. itemData is the Base64 of the serialized ItemStack bytes (deserialized on the main
     * thread).
     */
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

    /** Auction and hold source codes (source column). */
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
        // Every minute, release mails whose hold expired (safe with several servers: conditional
        // UPDATE)
        var _ =
                tasks.timer(
                        20 * 60,
                        20 * 60,
                        () -> {
                            var _ = tasks.async(this::releaseExpiredHolds);
                        });
    }

    // ------------------------------------------------------------------ writing

    /**
     * Inserts a mail within the caller's transaction (blocking, not on the main thread). Does
     * nothing if the same (recipient, source, reference ID) already exists.
     *
     * @param holdUntil auto-release time when inserted as HELD (null means admin release)
     * @return true if newly inserted, false if it already existed
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
            // The affected row count is 0 when ON DUPLICATE KEY UPDATE changes nothing
            return ps.executeUpdate() == 1;
        }
    }

    @Override
    public SqlConsumer sendItemInTx(UUID player, ItemStack item, String source, String refId) {
        byte[] data = item.serializeAsBytes();
        int season = api.infra().season().value();
        return c -> insert(c, player, season, "item", data, 0, source, refId, false, null);
    }

    /** Notifies the recipient after commit (including on other servers). */
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
     * Gives items back after commit: into the inventory when online (overflow to the mailbox),
     * otherwise to the mailbox. Main thread.
     *
     * @param refId reference ID used when mailed (deduplication)
     */
    public void giveOrMail(UUID player, List<ItemStack> items, String source, String refId) {
        Player online = Bukkit.getPlayer(player);
        int index = 0;
        for (ItemStack item : items) {
            String ref = refId + ":" + index++;
            // After the server-move snapshot was taken, inventory additions are not saved, so mail
            // it
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

    // ------------------------------------------------------------------ holds

    /** Virtual thread: sets mails whose hold expired to UNCLAIMED and notifies the recipients. */
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

    /**
     * Admin: releases held mails now (reference ID = auction:&lt;auction ID&gt;). Returns the
     * number of released mails.
     */
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

    // ------------------------------------------------------------------ screen

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

    /**
     * /우편함: list of unclaimed mails (including held ones). Clicking claims one; "claim all" is
     * {@link #claimAll}.
     */
    public void open(Player player) {
        var _ = load(player.getUniqueId()).thenAccept(mails -> menu.show(player, mails));
    }

    /** Unclaimed mails (including held, oldest first, up to 270). Completes on the main thread. */
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
     * Claim all (proposed): claims non-held mails one by one, oldest first (same CLAIMED
     * transaction). Items are claimed only while there is an empty slot and left otherwise. When
     * finished, reports the claimed and remaining counts and reopens the mailbox. Main thread.
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

    // ------------------------------------------------------------------ claiming

    /**
     * Auto-claim right after winning an auction: claimed at once if the buyer is online and has
     * room; otherwise it stays in the mailbox. Main thread.
     */
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
     * Claims one mail. The result (whether it was claimed) completes on the main thread.
     *
     * @param reopen reopen the mailbox when finished
     * @param notify send the result message (claim all notifies only once at the end)
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
                                            // After the server-move snapshot was taken, inventory
                                            // additions are not saved, so return it to the mailbox
                                            if (player.isOnline() && !api.players().frozen(uuid)) {
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
                                                // If the player left during the commit or is
                                                // moving servers, return it to the mailbox
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
