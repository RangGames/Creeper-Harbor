package wiki.creeper.harbor.core.inventory;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.session.ClaimResult;
import wiki.creeper.harbor.common.session.SessionLocks;
import wiki.creeper.harbor.core.admin.AdminLog;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.PlayerService;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Inventory history and restore (permission harbor.admin.inventory, proposed).
 *
 * <pre>
 * /harbor inventory history &lt;player&gt;             recent records (number, time, reason, server)
 * /harbor inventory view &lt;player&gt; [number]          current (or recorded) inventory in a read-only window
 * /harbor inventory restore &lt;player&gt; &lt;number&gt;      restore a record (immediately if on this server, DB if offline, refused if on another server)
 * </pre>
 */
public final class InventoryCommand {

    private static final int HISTORY_SHOWN = 15;

    private final InventorySync sync;
    private final PlayerService players;
    private final SessionLocks locks;

    /** Session lock owner while restoring an offline player ("admin-" + this server, proposed). */
    private final String lockOwner;

    private final MessageService messages;
    private final Tasks tasks;
    private final AdminLog adminLog;

    public InventoryCommand(
            InventorySync sync,
            PlayerService players,
            Redis redis,
            String serverId,
            MessageService messages,
            Tasks tasks,
            AdminLog adminLog) {
        this.sync = sync;
        this.players = players;
        this.locks = new SessionLocks(redis);
        this.lockOwner = "admin-" + serverId;
        SessionLocks.requireServerId(lockOwner);
        this.messages = messages;
        this.tasks = tasks;
        this.adminLog = adminLog;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("inventory")
                .then(
                        Commands.literal("history")
                                .then(
                                        Commands.argument("player", StringArgumentType.word())
                                                .executes(this::history)))
                .then(
                        Commands.literal("view")
                                .then(
                                        Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> view(ctx, null))
                                                .then(
                                                        Commands.argument(
                                                                        "id",
                                                                        LongArgumentType.longArg(1))
                                                                .executes(
                                                                        ctx ->
                                                                                view(
                                                                                        ctx,
                                                                                        LongArgumentType
                                                                                                .getLong(
                                                                                                        ctx,
                                                                                                        "id"))))))
                .then(
                        Commands.literal("restore")
                                .then(
                                        Commands.argument("player", StringArgumentType.word())
                                                .then(
                                                        Commands.argument(
                                                                        "id",
                                                                        LongArgumentType.longArg(1))
                                                                .executes(this::restore))));
    }

    private int history(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        var _ =
                players.find(name)
                        .thenAccept(
                                ref -> {
                                    if (ref.isEmpty()) {
                                        messages.send(
                                                sender,
                                                "inventory.unknown_player",
                                                Placeholder.unparsed("player", name));
                                        return;
                                    }
                                    var _ =
                                            sync.history(ref.get().uuid(), HISTORY_SHOWN)
                                                    .thenAcceptAsync(
                                                            rows -> {
                                                                messages.send(
                                                                        sender,
                                                                        "inventory.history_header",
                                                                        Placeholder.unparsed(
                                                                                "player",
                                                                                ref.get().name()),
                                                                        Placeholder.unparsed(
                                                                                "count",
                                                                                String.valueOf(
                                                                                        rows
                                                                                                .size())));
                                                                for (var row : rows) {
                                                                    messages.send(
                                                                            sender,
                                                                            "inventory.history_line",
                                                                            Placeholder.unparsed(
                                                                                    "id",
                                                                                    String.valueOf(
                                                                                            row
                                                                                                    .id())),
                                                                            Placeholder.unparsed(
                                                                                    "time",
                                                                                    InventorySync
                                                                                            .time(
                                                                                                    row
                                                                                                            .createdAt())),
                                                                            Placeholder.unparsed(
                                                                                    "reason",
                                                                                    row.reason()),
                                                                            Placeholder.unparsed(
                                                                                    "server",
                                                                                    row.server()));
                                                                }
                                                            },
                                                            tasks.main());
                                });
        return 1;
    }

    private int view(CommandContext<CommandSourceStack> ctx, @Nullable Long id) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player viewer)) {
            messages.send(sender, "inventory.player_only");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "player");
        var _ =
                players.find(name)
                        .thenAccept(
                                ref -> {
                                    if (ref.isEmpty()) {
                                        messages.send(
                                                sender,
                                                "inventory.unknown_player",
                                                Placeholder.unparsed("player", name));
                                        return;
                                    }
                                    var _ =
                                            sync.load(ref.get().uuid(), id)
                                                    .thenAcceptAsync(
                                                            data -> {
                                                                if (data.isEmpty()) {
                                                                    messages.send(
                                                                            sender,
                                                                            "inventory.not_found");
                                                                    return;
                                                                }
                                                                viewer.openInventory(
                                                                        sync.view(
                                                                                data.get(),
                                                                                ref.get().name()));
                                                            },
                                                            tasks.main());
                                });
        return 1;
    }

    private int restore(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        long id = LongArgumentType.getLong(ctx, "id");
        var _ =
                players.find(name)
                        .thenAccept(
                                ref -> {
                                    if (ref.isEmpty()) {
                                        messages.send(
                                                sender,
                                                "inventory.unknown_player",
                                                Placeholder.unparsed("player", name));
                                        return;
                                    }
                                    restore(sender, ref.get(), id);
                                });
        return 1;
    }

    private void restore(CommandSender sender, PlayerRef ref, long id) {
        UUID uuid = ref.uuid();
        var _ =
                sync.load(uuid, id)
                        .thenAcceptAsync(
                                data -> {
                                    if (data.isEmpty()) {
                                        messages.send(sender, "inventory.not_found");
                                        return;
                                    }
                                    Player online = Bukkit.getPlayer(uuid);
                                    if (online != null && sync.isSynced(uuid)) {
                                        sync.restoreOnline(online, data.get());
                                        done(sender, ref, id);
                                        return;
                                    }
                                    InventoryData record = data.get();
                                    var _ =
                                            tasks.supplyAsync(() -> restoreLocked(uuid, record))
                                                    .thenAcceptAsync(
                                                            claim -> {
                                                                switch (claim) {
                                                                    case CLAIMED, HANDOFF_CLAIMED ->
                                                                            done(sender, ref, id);
                                                                    case BUSY ->
                                                                            messages.send(
                                                                                    sender,
                                                                                    "inventory.online_elsewhere",
                                                                                    Placeholder
                                                                                            .unparsed(
                                                                                                    "player",
                                                                                                    ref
                                                                                                            .name()));
                                                                    case UNAVAILABLE ->
                                                                            messages.send(
                                                                                    sender,
                                                                                    "inventory.lock_unavailable");
                                                                }
                                                            },
                                                            tasks.main());
                                },
                                tasks.main());
    }

    /**
     * Virtual thread: takes the player's session lock under an admin pseudo-server id, writes the
     * restore, then releases it. A login in between waits on the lock (awaitClaim) instead of
     * loading the old row and later overwriting the restore. BUSY = the player is online somewhere
     * (or mid-move), so nothing is written.
     */
    private ClaimResult restoreLocked(UUID uuid, InventoryData record) {
        ClaimResult claim = locks.claim(uuid, lockOwner);
        if (!claim.isClaimed()) {
            return claim;
        }
        try {
            sync.restoreOffline(uuid, record).join();
        } finally {
            locks.release(uuid, lockOwner);
        }
        return claim;
    }

    private void done(CommandSender sender, PlayerRef ref, long id) {
        adminLog.record(sender, "inventory.restore", ref.uuid().toString(), String.valueOf(id));
        messages.send(
                sender,
                "inventory.restored",
                Placeholder.unparsed("player", ref.name()),
                Placeholder.unparsed("id", String.valueOf(id)));
    }
}
