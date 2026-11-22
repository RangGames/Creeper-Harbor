package wiki.creeper.harbor.wildlife.runtime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import wiki.creeper.harbor.wildlife.sim.FishOut;

/**
 * /harbor wildlife debug ('17-3' 9.5): state, temperament and phase Text Displays above fish, and
 * steering vector particles. Visible only to admins who turned it on. The entities are not saved
 * and carry the harbor:wild tag so leftovers get cleaned up.
 */
final class DebugOverlay {

    private static final double RANGE = 20;

    private final WildlifeKit kit;
    private final WildlifeManager manager;
    private final Map<UUID, Map<UUID, TextDisplay>> labels = new HashMap<>();

    DebugOverlay(WildlifeKit kit, WildlifeManager manager) {
        this.kit = kit;
        this.manager = manager;
    }

    void start() {
        var _ = kit.tasks.timer(10, 10, this::tick);
    }

    /** Toggles it. Returns true if now on. */
    boolean toggle(Player admin) {
        Map<UUID, TextDisplay> own = labels.remove(admin.getUniqueId());
        if (own != null) {
            own.values().forEach(TextDisplay::remove);
            return false;
        }
        labels.put(admin.getUniqueId(), new HashMap<>());
        return true;
    }

    void forget(UUID fish) {
        for (Map<UUID, TextDisplay> own : labels.values()) {
            TextDisplay d = own.remove(fish);
            if (d != null) {
                d.remove();
            }
        }
    }

    void shutdown() {
        labels.values().forEach(m -> m.values().forEach(TextDisplay::remove));
        labels.clear();
    }

    private void tick() {
        Iterator<Map.Entry<UUID, Map<UUID, TextDisplay>>> it = labels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Map<UUID, TextDisplay>> e = it.next();
            Player admin = Bukkit.getPlayer(e.getKey());
            if (admin == null) {
                e.getValue().values().forEach(TextDisplay::remove);
                it.remove();
                continue;
            }
            draw(admin, e.getValue());
        }
    }

    private void draw(Player admin, Map<UUID, TextDisplay> own) {
        World w = admin.getWorld();
        Location l = Locs.of(admin);
        Set<UUID> seen = new HashSet<>();
        int phaseTick = Bukkit.getCurrentTick();
        for (FishOut o : manager.frame().nearby(w.getName(), l.getX(), l.getY(), l.getZ(), RANGE)) {
            seen.add(o.id());
            Location at = new Location(w, o.x(), o.y() + 0.6 + o.profile().radius(), o.z());
            TextDisplay label = own.get(o.id());
            if (label == null || !label.isValid()) {
                label =
                        w.spawn(
                                at,
                                TextDisplay.class,
                                d -> {
                                    d.setPersistent(false);
                                    d.setVisibleByDefault(false);
                                    d.setBillboard(Display.Billboard.CENTER);
                                    d.setTeleportDuration(10);
                                    d.getPersistentDataContainer()
                                            .set(
                                                    WildlifeKit.WILD_KEY,
                                                    PersistentDataType.STRING,
                                                    "debug");
                                });
                admin.showEntity(kit.plugin, label);
                own.put(o.id(), label);
            } else {
                label.teleport(at);
            }
            int phase = Math.floorMod(phaseTick + o.phaseOffset(), 1 << 16);
            label.text(
                    kit.api
                            .messages()
                            .get(
                                    "wildlife.admin.debug_label",
                                    Placeholder.unparsed("state", o.state().name()),
                                    Placeholder.unparsed("group", o.profile().group().key()),
                                    Placeholder.unparsed("species", o.profile().species().name()),
                                    Placeholder.unparsed("anim", o.animation()),
                                    Placeholder.unparsed("phase", String.valueOf(phase % 20)),
                                    Placeholder.unparsed(
                                            "visible",
                                            String.valueOf(
                                                    manager.visibleTo(
                                                            o.id(), admin.getUniqueId()))),
                                    Placeholder.unparsed(
                                            "dist",
                                            String.format(
                                                    java.util.Locale.ROOT, "%.0f", o.nearest()))));
            // steering vectors: velocity (white dust) and desired direction (flame)
            for (int i = 1; i <= 4; i++) {
                double k = i * 0.25;
                admin.spawnParticle(
                        Particle.END_ROD,
                        o.x() + o.vx() * k,
                        o.y() + o.vy() * k,
                        o.z() + o.vz() * k,
                        1,
                        0,
                        0,
                        0,
                        0);
                admin.spawnParticle(
                        Particle.ELECTRIC_SPARK,
                        o.x() + o.steerX() * k,
                        o.y() + o.steerY() * k,
                        o.z() + o.steerZ() * k,
                        1,
                        0,
                        0,
                        0,
                        0);
            }
        }
        own.entrySet()
                .removeIf(
                        e -> {
                            if (seen.contains(e.getKey())) {
                                return false;
                            }
                            e.getValue().remove();
                            return true;
                        });
    }

    int active() {
        return labels.size();
    }
}
