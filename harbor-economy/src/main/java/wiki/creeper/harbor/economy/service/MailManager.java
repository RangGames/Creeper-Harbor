package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.economy.api.MailService;

/**
 * 우편함 (추가 제안, docs 20 7.4). 수령은 트랜잭션에서 state를 CLAIMED로 바꾼 뒤 메인 스레드에서 지급한다. 칸이 모자라면 수령하지 않고 남겨 둔다.
 * 다른 서버에 있는 수신자에게는 harbor:mail로 알린다.
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
            Instant createdAt) {

        @Nullable ItemStack item() {
            return itemData == null
                    ? null
                    : ItemStack.deserializeBytes(Base64.getDecoder().decode(itemData));
        }
    }

    static final Channel<MailNotice> CHANNEL = new Channel<>("harbor:mail", MailNotice.class);

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;

    public MailManager(HarborApi api, Tasks tasks, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
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
    }

    @Override
    public CompletableFuture<Void> sendItem(
            UUID player, ItemStack item, String source, @Nullable String refId) {
        byte[] data = item.serializeAsBytes();
        return insert(player, "item", data, 0, source, refId);
    }

    @Override
    public CompletableFuture<Void> sendGold(
            UUID player, long gold, String source, @Nullable String refId) {
        if (gold <= 0) {
            throw new IllegalArgumentException("골드는 0보다 커야 합니다");
        }
        return insert(player, "gold", null, gold, source, refId);
    }

    private CompletableFuture<Void> insert(
            UUID player,
            String kind,
            byte @Nullable [] data,
            long gold,
            String source,
            @Nullable String refId) {
        int season = api.infra().season().value();
        return api.database()
                .asyncVoid(
                        c -> {
                            try (PreparedStatement ps =
                                    c.prepareStatement(
                                            "INSERT INTO mailbox (uuid, season, kind, item_data, gold, source, ref_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
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
                                ps.executeUpdate();
                            }
                        })
                .thenRun(() -> api.bus().publish(CHANNEL, new MailNotice(player)));
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

    /** /우편함: 안 받은 우편 목록. 누르면 수령. */
    public void open(Player player) {
        UUID uuid = player.getUniqueId();
        var _ =
                api.database()
                        .async(
                                c -> {
                                    List<Mail> mails = new ArrayList<>();
                                    try (PreparedStatement ps =
                                            c.prepareStatement(
                                                    "SELECT id, kind, item_data, gold, source, created_at FROM mailbox"
                                                            + " WHERE uuid = ? AND state = 'UNCLAIMED' ORDER BY id LIMIT 270")) {
                                        ps.setString(1, uuid.toString());
                                        try (ResultSet rs = ps.executeQuery()) {
                                            while (rs.next()) {
                                                byte[] data = rs.getBytes(3);
                                                mails.add(
                                                        new Mail(
                                                                rs.getLong(1),
                                                                rs.getString(2),
                                                                data == null
                                                                        ? null
                                                                        : Base64.getEncoder()
                                                                                .encodeToString(
                                                                                        data),
                                                                rs.getLong(4),
                                                                rs.getString(5),
                                                                rs.getTimestamp(6).toInstant()));
                                            }
                                        }
                                    }
                                    return mails;
                                })
                        .thenAcceptAsync(mails -> show(player, mails), tasks.main());
    }

    private void show(Player player, List<Mail> mails) {
        if (mails.isEmpty()) {
            api.messages().send(player, "economy.mail.empty");
            return;
        }
        PagedMenu<Mail> menu =
                new PagedMenu<>(
                        api.messages().get("economy.mail.title"),
                        6,
                        mails,
                        mail -> new PagedMenu.Entry(icon(mail), e -> claim(player, mail)),
                        named(Material.ARROW, "economy.mail.previous"),
                        named(Material.ARROW, "economy.mail.next"));
        menu.show(player, 0);
    }

    private ItemStack icon(Mail mail) {
        ItemStack item = mail.item();
        if (item != null) {
            return item.clone();
        }
        ItemStack gold = ItemStack.of(Material.GOLD_NUGGET);
        gold.setData(
                DataComponentTypes.ITEM_NAME,
                api.messages()
                        .get(
                                "economy.mail.gold",
                                Placeholder.unparsed("gold", String.valueOf(mail.gold()))));
        return gold;
    }

    private ItemStack named(Material material, String key) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, api.messages().get(key));
        return stack;
    }

    private void claim(Player player, Mail mail) {
        UUID uuid = player.getUniqueId();
        ItemStack item = mail.item();
        if (item != null && player.getInventory().firstEmpty() < 0) {
            api.messages().send(player, "economy.mail.full");
            return;
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
                                        }
                                        return ok;
                                    },
                                    tasks.main());
        }
        var _ =
                claimed.exceptionally(
                                e -> {
                                    log.warn("우편 수령 실패: {} {}", uuid, mail.id(), e);
                                    return false;
                                })
                        .thenAcceptAsync(
                                ok -> {
                                    api.messages()
                                            .send(
                                                    player,
                                                    ok
                                                            ? "economy.mail.claimed"
                                                            : "economy.mail.failed");
                                    if (player.isOnline()) {
                                        open(player);
                                    }
                                },
                                tasks.main());
    }
}
