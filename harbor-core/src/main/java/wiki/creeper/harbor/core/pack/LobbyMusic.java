package wiki.creeper.harbor.core.pack;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.SoundTable;
import wiki.creeper.harbor.core.api.ActivePack;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Lobby background music (lobby server only, proposed): balance.yml {@code lobby.music} played to
 * every player in the lobby and replayed when the track ends, the same way World plays region music
 * (cuts vanilla music on each restart). It starts on join (packs already applied when returning
 * from a main server) and restarts once a resource pack finishes loading, since a pack sound sent
 * before the pack is loaded is silent.
 */
public final class LobbyMusic implements Listener {

    private static final long PERIOD = 20;
    private static final int DEFAULT_LOOP_TICKS = 6000;
    private static final HarborSounds.Options OPTIONS =
            HarborSounds.Options.DEFAULT.withSource(Sound.Source.MUSIC);

    private static final class State {
        @Nullable String music;
        long ticksLeft;
    }

    private final Supplier<ContentSnapshot> content;
    private final Map<UUID, State> states = new HashMap<>();

    public LobbyMusic(Supplier<ContentSnapshot> content) {
        this.content = content;
    }

    public void start(Tasks tasks) {
        var _ = tasks.timer(PERIOD, PERIOD, this::tick);
    }

    private void tick() {
        String music = content.get().single(Balance.TYPE).string("lobby.music").orElse("");
        @Nullable String wanted = music.isBlank() ? null : music;
        for (Player player : Bukkit.getOnlinePlayers()) {
            State state = states.computeIfAbsent(player.getUniqueId(), u -> new State());
            if (!Objects.equals(wanted, state.music)) {
                if (state.music != null) {
                    player.stopSound(SoundStop.source(Sound.Source.MUSIC));
                }
                state.music = wanted;
                state.ticksLeft = 0;
            }
            if (wanted == null) {
                continue;
            }
            state.ticksLeft -= PERIOD;
            if (state.ticksLeft <= 0) {
                player.stopSound(SoundStop.source(Sound.Source.MUSIC));
                HarborSounds.play(player, wanted, OPTIONS);
                state.ticksLeft = loopTicks(wanted);
            }
        }
    }

    /** Restart from the next tick (the music key is compared there). */
    private void restart(Player player) {
        states.computeIfAbsent(player.getUniqueId(), u -> new State()).ticksLeft = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        restart(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPack(PlayerResourcePackStatusEvent event) {
        if (event.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            restart(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Track length from the served pack, or sounds.yml loop if that is longer (an intended pause);
     * sounds.yml loop alone without a pack length; 5 minutes if neither (proposed).
     */
    private int loopTicks(String key) {
        int length = ActivePack.soundTicks(key).orElse(0);
        String name = key.startsWith("harbor:") ? key.substring("harbor:".length()) : key;
        int configured =
                content.get()
                        .single(SoundTable.TYPE)
                        .event(name)
                        .map(SoundTable.Event::loopTicks)
                        .orElse(0);
        if (length > 0) {
            return Math.max(length, configured);
        }
        return configured > 0 ? configured : DEFAULT_LOOP_TICKS;
    }
}
