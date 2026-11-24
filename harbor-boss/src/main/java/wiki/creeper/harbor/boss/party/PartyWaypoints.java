package wiki.creeper.harbor.boss.party;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.WaypointService;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Party member positions on the locator bar ("8. System design" chapter 0: the locator bar shows
 * quest targets, treasure map points and party member positions, proposed). Every 20 ticks each
 * player sees markers for party members in the same world (balance.yml waypoint.party_range,
 * colors.party, styles.party). With {@code waypoint.hide_players} (default true) players stop
 * transmitting their own vanilla waypoint, so the bar shows party members only instead of everyone.
 * Main thread only.
 */
public final class PartyWaypoints {

    private static final String OWNER = "party";

    /** Vanilla default transmit range of players. */
    private static final double PLAYER_DEFAULT_RANGE = 6.0e7;

    private final HarborApi api;
    private final Tasks tasks;
    private final PartyManager parties;

    public PartyWaypoints(HarborApi api, Tasks tasks, PartyManager parties) {
        this.api = api;
        this.tasks = tasks;
        this.parties = parties;
    }

    public void start() {
        var _ = tasks.timer(20, 20, this::tick);
    }

    private void tick() {
        Balance balance = api.content().snapshot().single(Balance.TYPE);
        boolean enabled = balance.bool("waypoint.enabled", true);
        boolean hidePlayers = enabled && balance.bool("waypoint.hide_players", true);
        double range = balance.number("waypoint.party_range", 1024);
        String color = balance.string("waypoint.colors.party").orElse(null);
        String style = balance.string("waypoint.styles.party").orElse(null);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            transmit(viewer, !hidePlayers);
            List<WaypointService.Target> targets = new ArrayList<>();
            for (UUID member : parties.membersOf(viewer.getUniqueId())) {
                Player other = Bukkit.getPlayer(member);
                if (other == null
                        || other.equals(viewer)
                        || !other.getWorld().equals(viewer.getWorld())) {
                    continue;
                }
                targets.add(
                        new WaypointService.Target(
                                member.toString(),
                                Objects.requireNonNull(other.getLocation()),
                                color,
                                style,
                                range));
            }
            api.waypoints().set(viewer, OWNER, targets);
        }
    }

    /** Turns the player's own vanilla waypoint on or off (only writes on change). */
    private static void transmit(Player player, boolean on) {
        AttributeInstance range = player.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE);
        if (range == null) {
            return;
        }
        double want = on ? PLAYER_DEFAULT_RANGE : 0;
        if (range.getBaseValue() != want) {
            range.setBaseValue(want);
        }
    }
}
