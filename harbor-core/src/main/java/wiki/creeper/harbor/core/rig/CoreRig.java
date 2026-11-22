package wiki.creeper.harbor.core.rig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.BakedRig;
import wiki.creeper.harbor.common.rig.Quat;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.common.rig.RigPlayback;
import wiki.creeper.harbor.common.rig.RigSampler;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.common.rig.Xform;
import wiki.creeper.harbor.core.api.RigHit;
import wiki.creeper.harbor.core.api.RigInstance;

/**
 * RigInstance implementation: a group of real, non-persistent entities (root ItemDisplay + part
 * Display passengers + hitbox Interactions).
 */
final class CoreRig implements RigInstance {

    private static final Quaternionf NO_ROTATION = new Quaternionf();
    private static final Quaternionf ITEM_FLIP =
            new Quaternionf(
                    (float) Quat.ITEM_FLIP.x(),
                    (float) Quat.ITEM_FLIP.y(),
                    (float) Quat.ITEM_FLIP.z(),
                    (float) Quat.ITEM_FLIP.w());

    private final RigManager manager;
    private final UUID id;
    private final BakedRig baked;
    private final RigPlayback playback;
    private final ItemDisplay root;
    private final Display[] parts;
    private final Interaction[] hitboxes;
    private final double scale;
    private final @Nullable Consumer<RigHit> onHit;
    private final List<Consumer<RemoveReason>> removeListeners = new ArrayList<>();
    private final List<BiConsumer<String, String>> eventListeners = new ArrayList<>();
    private @Nullable Set<UUID> viewers;
    private float yaw;
    private float pitch;
    private float entityYaw;
    private float entityPitch;
    private int moveTicks;
    private boolean dormant = true;
    private boolean valid = true;

    CoreRig(
            RigManager manager,
            UUID id,
            BakedRig baked,
            RigPlayback playback,
            ItemDisplay root,
            Display[] parts,
            Interaction[] hitboxes,
            double scale,
            @Nullable Consumer<RigHit> onHit,
            @Nullable Set<UUID> viewers,
            float yaw,
            float pitch,
            int moveTicks) {
        this.manager = manager;
        this.id = id;
        this.baked = baked;
        this.playback = playback;
        this.root = root;
        this.parts = parts.clone();
        this.hitboxes = hitboxes.clone();
        this.scale = scale;
        this.onHit = onHit;
        this.viewers = viewers == null ? null : new HashSet<>(viewers);
        this.moveTicks = moveTicks;
        this.yaw = yaw;
        this.pitch = pitch;
        float[] heading = RigSampler.entityHeading(baked.def(), yaw, pitch);
        this.entityYaw = heading[0];
        this.entityPitch = heading[1];
    }

    // ------------------------------------------------------------------ Transform helpers

    /**
     * Rig-origin transform → Display transform. Pre-multiplies the instance scale, and item parts
     * correct the flip with the right rotation.
     */
    static Transformation toBukkit(Xform pose, double scale, boolean flip) {
        Xform x = scale == 1.0 ? pose : Xform.uniform(scale).then(pose);
        Vec3 t = x.translation();
        Quat q = x.rotation();
        Vec3 s = x.scale();
        return new Transformation(
                new Vector3f((float) t.x(), (float) t.y(), (float) t.z()),
                new Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w()),
                new Vector3f((float) s.x(), (float) s.y(), (float) s.z()),
                flip ? new Quaternionf(ITEM_FLIP) : new Quaternionf(NO_ROTATION));
    }

    BakedRig baked() {
        return baked;
    }

    Display[] parts() {
        return parts;
    }

    Interaction[] hitboxEntities() {
        return hitboxes;
    }

    // ------------------------------------------------------------------ Tick

    /**
     * One tick update. Skipped when nobody is watching or the nearest viewer is beyond the LOD
     * distance; resynced at the current position once visible again.
     *
     * @return whether animation commands were sent (active)
     */
    boolean tick(long now, RigSettings settings) {
        if (!root.isValid()) {
            manager.remove(this, RemoveReason.ENTITY_GONE);
            return false;
        }
        if (hitboxes.length > 0 && now % settings.hitboxInterval() == 0) {
            updateHitboxes(now);
        }
        Set<Player> trackers = root.getTrackedBy();
        if (trackers.isEmpty()) {
            dormant = true;
            return false;
        }
        Location here = root.getLocation();
        double nearest = Double.MAX_VALUE;
        for (Entity viewer : trackers) {
            // Take it as Entity so Entity's getLocation is used, not OfflinePlayer's nullable one
            Location at = viewer.getLocation();
            if (Objects.equals(at.getWorld(), here.getWorld())) {
                nearest = Math.min(nearest, at.distanceSquared(here));
            }
        }
        double lod = settings.lodDistance();
        if (nearest > lod * lod) {
            dormant = true;
            return false;
        }
        if (dormant) {
            dormant = false;
            playback.resync();
        }
        playback.update(now, this::send);
        return true;
    }

    private void send(int part, Xform target, int duration) {
        Display d = parts[part];
        BakedRig.PartRef ref = baked.parts().get(part);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(duration);
        d.setTransformation(toBukkit(target, scale, ref.part().flip()));
    }

    void updateHitboxes(long now) {
        if (hitboxes.length == 0) {
            return;
        }
        Location base = root.getLocation();
        Quat orientation = Quat.entity(entityYaw, entityPitch);
        for (int h = 0; h < hitboxes.length; h++) {
            Interaction box = hitboxes[h];
            if (!box.isValid()) {
                continue;
            }
            Vec3 center = orientation.rotate(playback.hitbox(h, now).mul(scale));
            RigDef.Hitbox def = baked.hitboxes().get(h).hitbox();
            double height = def.height() * scale * baked.def().scale();
            Location at = base.clone().add(center.x(), center.y() - height / 2, center.z());
            at.setYaw(0);
            at.setPitch(0);
            if (box.getLocation().distanceSquared(at) > 1.0e-4) {
                var _ = box.teleport(at);
            }
        }
    }

    /** Bone name of a hitbox index. */
    String hitboxBone(int index) {
        return baked.hitboxes().get(index).name();
    }

    @Nullable Consumer<RigHit> hitHandler() {
        return onHit;
    }

    // ------------------------------------------------------------------ RigInstance

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public RigId rig() {
        return baked.def().id();
    }

    @Override
    public boolean isValid() {
        return valid && root.isValid();
    }

    @Override
    public Location location() {
        return root.getLocation();
    }

    @Override
    public Entity root() {
        return root;
    }

    @Override
    public List<Entity> entities() {
        List<Entity> all = new ArrayList<>(1 + parts.length + hitboxes.length);
        all.add(root);
        all.addAll(List.of(parts));
        all.addAll(List.of(hitboxes));
        return all;
    }

    @Override
    public void moveTo(Location to) {
        moveTo(to, moveTicks);
    }

    @Override
    public void moveTo(Location to, int ticks) {
        RigManager.requireMain();
        if (!valid || root.getVehicle() != null) {
            return;
        }
        int duration = Math.max(0, ticks);
        if (root.getTeleportDuration() != duration) {
            root.setTeleportDuration(duration);
        }
        Location dest = to.clone();
        dest.setYaw(entityYaw);
        dest.setPitch(entityPitch);
        var _ = root.teleport(dest); // 26.x teleports with passengers (the EntityState flags are
        // deprecated)
    }

    @Override
    public void setHeading(float newYaw, float newPitch) {
        RigManager.requireMain();
        if (!valid) {
            return;
        }
        yaw = newYaw;
        pitch = newPitch;
        float[] heading = RigSampler.entityHeading(baked.def(), newYaw, newPitch);
        if (Math.abs(heading[0] - entityYaw) < 0.01f
                && Math.abs(heading[1] - entityPitch) < 0.01f) {
            return;
        }
        entityYaw = heading[0];
        entityPitch = heading[1];
        root.setRotation(entityYaw, entityPitch);
        for (Display d : parts) {
            d.setRotation(entityYaw, entityPitch);
        }
    }

    @Override
    public float yaw() {
        return yaw;
    }

    @Override
    public float pitch() {
        return pitch;
    }

    @Override
    public void mount(Entity vehicle) {
        RigManager.requireMain();
        if (!valid) {
            return;
        }
        if (root.getVehicle() != null) {
            var _ = root.leaveVehicle();
        }
        var _ = vehicle.addPassenger(root);
    }

    @Override
    public void dismount() {
        RigManager.requireMain();
        if (valid && root.getVehicle() != null) {
            var _ = root.leaveVehicle();
        }
    }

    @Override
    public @Nullable Entity vehicle() {
        return root.getVehicle();
    }

    @Override
    public boolean play(String animation) {
        return play(animation, manager.settings().blendTicks());
    }

    @Override
    public boolean play(String animation, int blendTicks) {
        RigManager.requireMain();
        if (!valid) {
            return false;
        }
        if (animation.equals(playback.animation())) {
            return true;
        }
        return playback.play(animation, blendTicks, 0, manager.now());
    }

    @Override
    public boolean playOnce(String animation, @Nullable String returnTo) {
        RigManager.requireMain();
        if (!valid) {
            return false;
        }
        return playback.playOnce(
                animation, returnTo, manager.settings().blendTicks(), manager.now());
    }

    @Override
    public void stop() {
        RigManager.requireMain();
        playback.stop(manager.settings().blendTicks(), manager.now());
    }

    @Override
    public @Nullable String animation() {
        return playback.animation();
    }

    @Override
    public void setPhase(int frame) {
        RigManager.requireMain();
        playback.setPhase(frame, manager.now());
    }

    @Override
    public int frame() {
        return playback.frame(manager.now());
    }

    @Override
    public int frameCount() {
        return playback.frameCount();
    }

    @Override
    public void setSpeed(double speed) {
        RigManager.requireMain();
        playback.setSpeed(speed, manager.now());
    }

    @Override
    public double speed() {
        return playback.speed();
    }

    @Override
    public boolean isPublic() {
        return viewers == null;
    }

    @Override
    public Set<UUID> viewers() {
        Set<UUID> current = viewers;
        return current == null ? Set.of() : Set.copyOf(current);
    }

    @Override
    public void setViewers(Collection<? extends Player> players) {
        RigManager.requireMain();
        if (!valid) {
            return;
        }
        Set<UUID> next = new HashSet<>();
        players.forEach(p -> next.add(p.getUniqueId()));
        Set<UUID> previous = viewers;
        if (previous == null) {
            // Public rig → private rig: switch to hidden by default and show only to the target
            for (Entity e : entities()) {
                e.setVisibleByDefault(false);
            }
            viewers = next;
            for (Player p : players) {
                show(p);
            }
            return;
        }
        for (UUID old : previous) {
            if (!next.contains(old)) {
                Player p = Bukkit.getPlayer(old);
                if (p != null) {
                    hide(p);
                }
            }
        }
        for (Player p : players) {
            if (!previous.contains(p.getUniqueId())) {
                show(p);
            }
        }
        viewers = next;
    }

    @Override
    public void addViewer(Player player) {
        RigManager.requireMain();
        if (!valid) {
            return;
        }
        Set<UUID> current = viewers;
        if (current != null) {
            current.add(player.getUniqueId());
        }
        show(player);
    }

    @Override
    public void removeViewer(Player player) {
        RigManager.requireMain();
        if (!valid) {
            return;
        }
        Set<UUID> current = viewers;
        if (current != null) {
            current.remove(player.getUniqueId());
        }
        hide(player); // For a public rig, hide it from this player only
    }

    @Override
    public void showToAll() {
        RigManager.requireMain();
        if (!valid || viewers == null) {
            return;
        }
        viewers = null;
        for (Entity e : entities()) {
            e.setVisibleByDefault(true);
        }
    }

    /**
     * Removes a player who quit from the viewers (entity hiding is meaningless once disconnected).
     */
    void forget(UUID player) {
        Set<UUID> current = viewers;
        if (current != null) {
            current.remove(player);
        }
    }

    private void show(Player p) {
        for (Entity e : entities()) {
            p.showEntity(manager.plugin(), e);
        }
    }

    private void hide(Player p) {
        for (Entity e : entities()) {
            p.hideEntity(manager.plugin(), e);
        }
    }

    @Override
    public void setPartGlow(String bone, boolean glow) {
        RigManager.requireMain();
        for (int i = 0; i < parts.length; i++) {
            BakedRig.PartRef ref = baked.parts().get(i);
            if (bone.equals(ALL_PARTS) || ref.name().equals(bone)) {
                parts[i].setGlowColorOverride(glow ? Color.fromRGB(ref.part().glowColor()) : null);
                parts[i].setGlowing(glow);
            }
        }
    }

    @Override
    public void onEvent(BiConsumer<String, String> listener) {
        if (!valid) {
            return;
        }
        if (eventListeners.isEmpty()) {
            playback.setEventSink(this::fireEvent);
        }
        eventListeners.add(listener);
    }

    private void fireEvent(String animation, String event) {
        for (BiConsumer<String, String> listener : List.copyOf(eventListeners)) {
            try {
                listener.accept(animation, event);
            } catch (RuntimeException e) {
                // Don't let one module listener's exception stop the whole playback tick (other
                // rigs were skipped, and for looping animations it repeated every time)
                System.getLogger("Harbor/Rig")
                        .log(
                                System.Logger.Level.ERROR,
                                "리그 이벤트 리스너 오류: " + animation + "/" + event,
                                e);
            }
        }
    }

    @Override
    public void onRemove(Consumer<RemoveReason> listener) {
        if (!valid) {
            listener.accept(RemoveReason.REQUESTED);
            return;
        }
        removeListeners.add(listener);
    }

    @Override
    public void remove() {
        RigManager.requireMain();
        manager.remove(this, RemoveReason.REQUESTED);
    }

    /** Removes the entities and sends the removal notice (called only by RigManager). */
    void destroy(RemoveReason reason, Logger log) {
        if (!valid) {
            return;
        }
        valid = false;
        for (Entity e : entities()) {
            if (e.isValid()) {
                e.remove();
            }
        }
        for (Consumer<RemoveReason> listener : removeListeners) {
            try {
                listener.accept(reason);
            } catch (RuntimeException e) {
                log.error("리그 제거 알림 처리 중 오류 ({})", rig(), e);
            }
        }
        removeListeners.clear();
        eventListeners.clear();
        playback.setEventSink(null);
    }
}
