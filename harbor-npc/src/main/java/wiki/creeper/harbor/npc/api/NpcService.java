package wiki.creeper.harbor.npc.api;

import java.util.Collection;
import java.util.Optional;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
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
}
