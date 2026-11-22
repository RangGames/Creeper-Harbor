package wiki.creeper.harbor.boss.encounter;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.logic.Geometry;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.core.api.HarborSounds;

/**
 * Harpoon trajectory hit detection: tracks thrown harpoons (tridents) every tick and computes
 * intersections with the rig hitboxes directly. Main thread only.
 */
final class HarpoonTracker {

    /** A tracked harpoon. */
    private static final class Harpoon {
        final UUID thrower;
        final ItemDef def;
        final ItemStack stack;
        Location prev;
        int age;

        Harpoon(UUID thrower, ItemDef def, ItemStack stack, Location prev) {
            this.thrower = thrower;
            this.def = def;
            this.stack = stack;
            this.prev = prev;
        }
    }

    private final EncounterManager mgr;
    private final Map<UUID, Harpoon> harpoons = new HashMap<>();

    HarpoonTracker(EncounterManager mgr) {
        this.mgr = mgr;
    }

    /**
     * Harpoon throw (ProjectileLaunchEvent): only harpoons thrown inside an encounter arena are
     * tracked.
     */
    void launched(Trident trident, Player player) {
        Optional<ItemDef> harpoon = mgr.items().harpoon(trident.getItemStack());
        if (harpoon.isEmpty()) {
            return;
        }
        Encounter e = mgr.encounterOfPlayer(player.getUniqueId()).orElse(null);
        if (e == null) {
            e = worldEncounterAt(BossItems.loc(player));
        }
        if (e == null || !e.inArena(BossItems.loc(player))) {
            return;
        }
        harpoons.put(
                trident.getUniqueId(),
                new Harpoon(
                        player.getUniqueId(),
                        harpoon.get(),
                        trident.getItemStack().clone(),
                        BossItems.loc(trident)));
        // Harpoon throw (shared, '15. Sound' 4.3). Without the pack, only the vanilla trident sound
        HarborSounds.playAt(BossItems.loc(player), "harbor:boss.harpoon_throw");
    }

    private @Nullable Encounter worldEncounterAt(Location loc) {
        for (Encounter e : mgr.active()) {
            if (e.def.isWorld() && e.inArena(loc)) {
                return e;
            }
        }
        return null;
    }

    /**
     * Projectiles do not collide with Interactions, so each tick the intersection of the previous →
     * current position segment with the hitbox (AABB) is computed directly.
     */
    void track() {
        if (harpoons.isEmpty()) {
            return;
        }
        int maxAge = mgr.balance().harpoonTrackTicks();
        Iterator<Map.Entry<UUID, Harpoon>> it = harpoons.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Harpoon> entry = it.next();
            Harpoon h = entry.getValue();
            Entity entity = Bukkit.getEntity(entry.getKey());
            Player thrower = Bukkit.getPlayer(h.thrower);
            if (!(entity instanceof Trident trident)
                    || !trident.isValid()
                    || thrower == null
                    || ++h.age > maxAge) {
                it.remove();
                continue;
            }
            Location now = BossItems.loc(trident);
            Encounter e = mgr.encounterOfPlayer(h.thrower).orElse(null);
            if (e == null) {
                e = worldEncounterAt(now);
            }
            if (e == null || !now.getWorld().equals(e.world)) {
                it.remove();
                continue;
            }
            double bestT = Double.MAX_VALUE;
            String bestBone = null;
            for (Map.Entry<Interaction, String> box : e.hitboxes()) {
                Interaction i = box.getKey();
                Location bl = BossItems.loc(i);
                Geometry.Aabb aabb =
                        Geometry.Aabb.interaction(
                                        bl.getX(),
                                        bl.getY(),
                                        bl.getZ(),
                                        i.getInteractionWidth(),
                                        i.getInteractionHeight())
                                .grow(0.15);
                double t =
                        aabb.intersect(
                                h.prev.getX(),
                                h.prev.getY(),
                                h.prev.getZ(),
                                now.getX(),
                                now.getY(),
                                now.getZ());
                if (t >= 0 && t < bestT) {
                    bestT = t;
                    bestBone = box.getValue();
                }
            }
            h.prev = now;
            if (bestBone == null) {
                if (trident.isInBlock()) {
                    it.remove();
                }
                continue;
            }
            it.remove();
            trident.remove();
            if (BossItems.stat(h.def, BossItems.STAT_RECOVER, 0) > 0) {
                Map<Integer, ItemStack> left = thrower.getInventory().addItem(h.stack);
                left.values().forEach(s -> thrower.getWorld().dropItem(BossItems.loc(thrower), s));
            }
            e.harpoonHit(thrower, h.def, bestBone);
        }
    }

    void clear() {
        harpoons.clear();
    }
}
