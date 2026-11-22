package wiki.creeper.harbor.npc.api;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * NPC lookup (docs 20 5.2). Registered in the Bukkit ServicesManager. Use on the main thread.
 *
 * <pre>{@code
 * NpcService npcs = Harbor.api().service(NpcService.class).orElseThrow();
 * if (npcs.isNear(player, NpcId.parse("npc/bawi"), 6)) { ... }
 * }</pre>
 */
public interface NpcService {

    Optional<NpcInfo> npc(NpcId id);

    Collection<NpcInfo> all();

    /**
     * Where it is standing now. For wanderers, the current candidate location; empty if spawn
     * conditions do not match or it is on another server.
     */
    Optional<Location> location(NpcId id);

    /** The nearest (standing) NPC within the radius. */
    Optional<NpcInfo> nearest(Player player, double radius);

    boolean isNear(Player player, NpcId id, double radius);

    /** The name this player sees (reflects name_variants). */
    Component displayName(Player viewer, NpcId id);

    /**
     * State check that blocks NPC clicks and cutscene starts (minigames, boss fights, etc.). NPC
     * does not know other game modules, so the module that knows the state registers it. Removed
     * automatically when owner is disabled.
     *
     * @param reason reason code. Notice message key is {@code npc.busy.<reason>} (otherwise
     *     npc.busy.default)
     */
    void registerBusyCheck(Plugin owner, String reason, Predicate<Player> busy);

    /** The first reason hit among registered checks (cutscene while a cutscene is playing). */
    Optional<String> busyReason(Player player);

    // ------------------------------------------------------------------ placement control

    /**
     * Places the NPC here (or at its configured location: npcs.yml location, or the wanderer's
     * current candidate) regardless of spawn_flag, hide_flag and the wanderer schedule. The
     * override lives in memory only: it is cleared by {@link #release}, when duration runs out, on
     * Harbor-Npc disable and on restart, and the NPC then goes back to its content placement. NPC
     * entities exist only on the main server (role main); elsewhere this returns false.
     *
     * @param at null = the configured location
     * @param duration null = until released (temporary script spawns pass a duration)
     * @return false if the NPC is unknown, entities are disabled on this server or there is no
     *     location to use
     */
    boolean spawn(NpcId id, @Nullable Location at, @Nullable Duration duration);

    /**
     * Hides the NPC until {@link #release} (memory only, like {@link #spawn}).
     *
     * @return false if the NPC is unknown or entities are disabled on this server
     */
    boolean despawn(NpcId id);

    /**
     * Moves the NPC (same override as {@link #spawn} with a location; an existing duration is
     * kept). The entities are recreated at the new location.
     */
    boolean teleport(NpcId id, Location to);

    /** Drops the placement override and the gaze override; the NPC follows its content again. */
    boolean release(NpcId id);

    /** Whether a placement override ({@link #spawn} · {@link #despawn}) is active. */
    boolean controlled(NpcId id);

    /**
     * Makes the NPC keep looking at this location instead of the nearest player, until {@link
     * #resetLook} or {@link #release}.
     */
    boolean lookAt(NpcId id, Location target);

    /**
     * Makes the NPC follow this player with its gaze (while the player is online and in the same
     * world), until {@link #resetLook}, {@link #release} or the player quits.
     */
    boolean lookAt(NpcId id, Player target);

    /** Back to the default gaze (nearest player within look_radius). */
    void resetLook(NpcId id);
}
