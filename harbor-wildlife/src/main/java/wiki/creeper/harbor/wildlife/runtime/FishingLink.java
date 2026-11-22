package wiki.creeper.harbor.wildlife.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.ShadowDriver;
import wiki.creeper.harbor.fishing.api.event.FishBiteEvent;
import wiki.creeper.harbor.fishing.api.event.FishingCastEvent;
import wiki.creeper.harbor.fishing.api.event.FishingSessionEndEvent;
import wiki.creeper.harbor.fishing.api.event.MinigameEndEvent;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.api.WildState;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;
import wiki.creeper.harbor.wildlife.sim.FishOut;
import wiki.creeper.harbor.wildlife.sim.MediumField;

/**
 * Feeding and bait reactions (Fishing link, '17-3' 9.4). One way, Wildlife → Fishing: listens to
 * Fishing events and calls only the Fishing api (claimShadow, ShadowDriver). Wild fish do not
 * affect catch rolls (the roll result picks the presentation).
 *
 * <ol>
 *   <li>FishingCastEvent: up to 2 CRUISE fish of the same medium within 10 blocks APPROACH, with a
 *       per-temperament chance
 *   <li>If an approaching fish matches the rolled species, it takes over the pending shadow via
 *       claimShadow; on bite it plays bite, sinks under the bobber, and the Fishing shadow takes
 *       over from the same spot
 *   <li>FishBiteEvent: the other approaching fish dart away "beaten to it" and return to CRUISE
 *   <li>MinigameEndEvent, FishingSessionEndEvent: releases the approaching fish. If the line snaps,
 *       nearby fish are startled
 * </ol>
 */
final class FishingLink implements Listener {

    /** Fish that approached for one cast. */
    private record Cast(Location hook, List<UUID> approachers, @Nullable UUID claimed) {}

    private final WildlifeKit kit;
    private final WildlifeManager manager;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final Map<UUID, Cast> casts = new HashMap<>();

    FishingLink(WildlifeKit kit, WildlifeManager manager) {
        this.kit = kit;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onCast(FishingCastEvent event) {
        Player player = event.getPlayer();
        release(player.getUniqueId(), false);
        Location hook = event.hook();
        World w = hook.getWorld();
        if (w == null) {
            return;
        }
        WildlifeBalance bal = kit.balance();
        MediumField field = kit.grid.field(w.getName());
        Medium medium =
                field.is(Medium.LAVA, hook.getX(), hook.getY() - 0.5, hook.getZ())
                        ? Medium.LAVA
                        : Medium.WATER;
        List<FishOut> near =
                new ArrayList<>(
                        manager.frame()
                                .nearby(
                                        w.getName(),
                                        hook.getX(),
                                        hook.getY(),
                                        hook.getZ(),
                                        bal.baitRadius()));
        Collections.shuffle(near, new java.util.Random(random.nextLong()));
        List<UUID> busy = busyFish();
        List<FishOut> chosen = new ArrayList<>();
        for (FishOut o : near) {
            if (chosen.size() >= bal.baitMax()) {
                break;
            }
            if (o.hidden()
                    || busy.contains(o.id())
                    || (o.state() != WildState.CRUISE && o.state() != WildState.SCHOOL)
                    || o.profile().medium() == Medium.FLOOR
                    || o.profile().medium().cell() != medium) {
                continue;
            }
            if (random.nextDouble() < o.profile().groupDef().baitChance()) {
                chosen.add(o);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        UUID claimed = null;
        UUID angler = player.getUniqueId();
        Vec3 hookPos = new Vec3(hook.getX(), hook.getY(), hook.getZ());
        double seconds = event.waitTicks() / 20.0;
        List<UUID> ids = new ArrayList<>();
        for (FishOut o : chosen) {
            ids.add(o.id());
            UUID id = o.id();
            if (claimed == null && o.profile().fish().id().equals(event.fish().id())) {
                if (event.claimShadow(new WildShadow(id, angler, hook))) {
                    claimed = id;
                    // Fishing also calls ShadowDriver.approach (this just sets the same value
                    // again)
                    manager.engine()
                            .submit(world -> world.approach(id, hookPos, seconds, true, angler));
                    continue;
                }
            }
            manager.engine().submit(world -> world.approach(id, hookPos, seconds, false, angler));
        }
        casts.put(angler, new Cast(hook, List.copyOf(ids), claimed));
    }

    private List<UUID> busyFish() {
        List<UUID> busy = new ArrayList<>();
        for (Cast c : casts.values()) {
            busy.addAll(c.approachers());
        }
        return busy;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBite(FishBiteEvent event) {
        Cast c = casts.get(event.getPlayer().getUniqueId());
        if (c == null) {
            return;
        }
        for (UUID id : c.approachers()) {
            if (!id.equals(c.claimed())) {
                manager.engine().submit(world -> world.stolen(id));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMinigameEnd(MinigameEndEvent event) {
        release(event.getPlayer().getUniqueId(), event.result() == MinigameResult.LINE_BROKE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSessionEnd(FishingSessionEndEvent event) {
        release(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        release(event.getPlayer().getUniqueId(), false);
    }

    private void release(UUID angler, boolean lineBroke) {
        Cast c = casts.remove(angler);
        if (c == null) {
            return;
        }
        manager.engine().submit(world -> world.releaseAngler(angler, lineBroke));
        World w = c.hook().getWorld();
        if (lineBroke && w != null) {
            Vec3 at = new Vec3(c.hook().getX(), c.hook().getY(), c.hook().getZ());
            double r = kit.balance().lineBreakRadius();
            String name = w.getName();
            manager.engine().submit(world -> world.threatAt(name, at, r));
        }
    }

    int activeCasts() {
        return casts.size();
    }

    /**
     * Stand-in for the pending shadow (Fishing api SPI). Every method is called on the main thread
     * and only queues requests to the simulation thread. Positions are as of the last simulation
     * frame.
     */
    private final class WildShadow implements ShadowDriver {

        private final UUID fish;
        private final UUID angler;
        private Location last;

        WildShadow(UUID fish, UUID angler, Location hook) {
            this.fish = fish;
            this.angler = angler;
            this.last = hook.clone();
        }

        @Override
        public void approach(Location hook, int ticks) {
            Vec3 h = new Vec3(hook.getX(), hook.getY(), hook.getZ());
            double seconds = ticks / 20.0;
            manager.engine().submit(world -> world.approach(fish, h, seconds, true, angler));
        }

        @Override
        public void bite() {
            manager.engine().submit(world -> world.bite(fish));
        }

        @Override
        public Location location() {
            FishOut o = manager.frame().get(fish);
            World w = last.getWorld();
            if (o != null && w != null) {
                last = new Location(w, o.x(), o.y(), o.z(), o.yaw(), o.pitch());
            }
            return last.clone();
        }

        @Override
        public boolean isValid() {
            return manager.alive(fish);
        }

        @Override
        public void release(boolean caught) {
            manager.engine().submit(world -> world.release(fish, caught, null));
        }
    }
}
