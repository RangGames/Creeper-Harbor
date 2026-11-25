package wiki.creeper.harbor.quest.service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.WaypointService;

/**
 * Locator bar waypoint for the tracked quest (docs 20 6.4 "tracking display"): the current
 * objective (or the turn-in NPC) of the one tracked quest is shown through Core {@link
 * WaypointService}, refreshed every 20 ticks so wandering NPCs are followed. A target farther than
 * {@code quest.tracker.locator_clamp_m} is drawn at that distance along the same direction, so the
 * marker never sits in an unloaded chunk (the locator bar only shows direction). Hidden during
 * cutscenes. balance.yml {@code quest.tracker.locator} turns it off. Main thread only.
 */
final class QuestLocator {

    static final String OWNER = "quest";

    private final QuestEngine engine;
    private final Tracker tracker;
    private final Set<UUID> shown = new HashSet<>();

    QuestLocator(QuestEngine engine, Tracker tracker) {
        this.engine = engine;
        this.tracker = tracker;
    }

    void refresh() {
        Balance balance = engine.balance();
        boolean enabled = balance.bool("quest.tracker.locator", true);
        for (HarborPlayer hp : engine.api.players().online()) {
            Player player = Bukkit.getPlayer(hp.uuid());
            if (player == null) {
                continue;
            }
            Optional<Tracker.TrackedTarget> target =
                    enabled && !engine.deps.cutscenes().isPlaying(player)
                            ? tracker.target(player, hp)
                            : Optional.empty();
            if (target.isEmpty()) {
                hide(player);
                continue;
            }
            Tracker.TrackedTarget t = target.get();
            String kind = t.ready() ? "quest_ready" : t.main() ? "quest_main" : "quest";
            engine.api
                    .waypoints()
                    .set(
                            player,
                            OWNER,
                            List.of(
                                    new WaypointService.Target(
                                            "tracked",
                                            clamp(
                                                    Objects.requireNonNull(player.getLocation()),
                                                    t.at(),
                                                    balance.number(
                                                            "quest.tracker.locator_clamp_m", 96)),
                                            balance.string("waypoint.colors." + kind)
                                                    .or(
                                                            () ->
                                                                    balance.string(
                                                                            "waypoint.colors.quest"))
                                                    .orElse(null),
                                            balance.string("waypoint.styles." + kind)
                                                    .or(
                                                            () ->
                                                                    balance.string(
                                                                            "waypoint.styles.quest"))
                                                    .orElse(null),
                                            balance.number("waypoint.quest_range", 4096))));
            shown.add(hp.uuid());
        }
    }

    /** Target pulled back to at most {@code max} blocks from the player on the same line. */
    static Location clamp(Location from, Location to, double max) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (max <= 0 || distance <= max) {
            return to;
        }
        double k = max / distance;
        return new Location(
                to.getWorld(), from.getX() + dx * k, from.getY() + dy * k, from.getZ() + dz * k);
    }

    private void hide(Player player) {
        if (shown.remove(player.getUniqueId())) {
            engine.api.waypoints().clear(player, OWNER);
        }
    }

    void forget(UUID uuid) {
        shown.remove(uuid);
    }

    /** Plugin shutdown: clears every marker. */
    void clear() {
        for (UUID uuid : List.copyOf(shown)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                engine.api.waypoints().clear(player, OWNER);
            }
        }
        shown.clear();
    }
}
