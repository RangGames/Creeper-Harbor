package wiki.creeper.harbor.fishing.session;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.SessionState;
import wiki.creeper.harbor.fishing.present.CatchPresenter;

/**
 * Session input and abort handling (docs 20 3.4 interruptions): minigame input, hotbar and drop
 * locks, aborts on death, teleport and quit, plus movement and chat activity for the MacroGuard AFK
 * signal. Main thread only, except chat (MacroGuard.activity is thread-safe).
 */
final class SessionGuards implements Listener {

    private final FishingManager fishing;
    private final Map<UUID, FishingSession> sessions;
    private final Map<UUID, TensionSessionImpl> tensions;
    private final MythicHolds mythic;
    private final CatchPresenter presenter;
    private final MacroGuard macro;

    SessionGuards(
            FishingManager fishing,
            Map<UUID, FishingSession> sessions,
            Map<UUID, TensionSessionImpl> tensions,
            MythicHolds mythic,
            CatchPresenter presenter,
            MacroGuard macro) {
        this.fishing = fishing;
        this.sessions = sessions;
        this.tensions = tensions;
        this.mythic = mythic;
        this.presenter = presenter;
        this.macro = macro;
    }

    /**
     * Pressing Shift (reel) on a boat dismounts, so dismounting is prevented during the minigame.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player && event.isCancellable() && locked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        FishingSession s = fishing.sessionOf(event.getPlayer());
        s.inputSneak = event.getInput().isSneak();
        s.inputLeft = event.getInput().isLeft();
        s.inputRight = event.getInput().isRight();
    }

    private boolean locked(Player player) {
        return fishing.session(player.getUniqueId()) == SessionState.MINIGAME
                || tensions.containsKey(player.getUniqueId());
    }

    /**
     * The hotbar stays locked only in the fishing minigame. In an external tension session (boss
     * anchor) switching items lowers the rod and pauses the line instead, so a solo player can also
     * use a harpoon or repair kit (proposed).
     */
    @EventHandler(ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        if (fishing.session(event.getPlayer().getUniqueId()) == SessionState.MINIGAME) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (locked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        fishing.abort(event.getPlayer(), MinigameResult.ESCAPED);
        closeTension(event.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        fishing.abort(event.getPlayer(), MinigameResult.ESCAPED);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        fishing.abort(player, MinigameResult.ESCAPED);
        closeTension(player);
        FishingSession s = sessions.remove(player.getUniqueId());
        if (s != null) {
            CastHandler.closeShadow(s, false);
            FishingSession.MythicHold hold = s.mythic;
            if (hold != null) {
                mythic.releaseMythic(hold);
            }
            mythic.discardPending(s);
        }
        presenter.forget(player);
        macro.reset(player.getUniqueId());
    }

    private void closeTension(Player player) {
        TensionSessionImpl t = tensions.get(player.getUniqueId());
        if (t != null) {
            t.close(MinigameResult.ESCAPED);
        }
    }

    // ------------------------------------------------------------------ MacroGuard activity

    /** Changing position counts as activity (turning the view alone does not). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedPosition()) {
            macro.activity(event.getPlayer().getUniqueId());
        }
    }

    /** Async chat thread: MacroGuard.activity only writes a concurrent map. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChat(AsyncChatEvent event) {
        macro.activity(event.getPlayer().getUniqueId());
    }
}
