package wiki.creeper.harbor.world.ship;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.SeaEventService;
import wiki.creeper.harbor.world.api.ShipFlag;
import wiki.creeper.harbor.world.api.ShipState;
import wiki.creeper.harbor.world.api.event.ShipWreckedEvent;
import wiki.creeper.harbor.world.content.SeaEventDef;
import wiki.creeper.harbor.world.content.ShipDef;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * Sailing. Every tick: speed correction, decoration following, sailing sound and icebreaking
 * (restoring melted ice); every second: storm durability damage and wrecking; also blocks entry
 * into storm waters and fixes the decoration seat on boarding. Main thread only.
 */
final class ShipNavigator {

    private static final Set<Material> ICE =
            Set.of(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    // Ship sounds ('15. sound' 4.4)
    private static final String HULL_HIT = "harbor:ship.hull_hit";

    /** Sailing sound plays only when moving at least this speed (blocks/tick) (proposed value). */
    private static final double ENGINE_MIN_SPEED = 0.05;

    private record Melt(Location at, Material type, long restoreAt) {}

    private final WorldKit kit;
    private final RegionManager regions;
    private final ShipFleet fleet;
    private @Nullable SeaEventService seaEvents;
    private final Deque<Melt> melted = new ArrayDeque<>();

    ShipNavigator(WorldKit kit, RegionManager regions, ShipFleet fleet) {
        this.kit = kit;
        this.regions = regions;
        this.fleet = fleet;
    }

    void seaEvents(SeaEventService service) {
        this.seaEvents = service;
    }

    /** Server shutdown: restores all melted ice. */
    void restoreAll() {
        while (!melted.isEmpty()) {
            Melt m = melted.poll();
            m.at().getBlock().setType(m.type());
        }
    }

    // ------------------------------------------------------------------ speed and storms

    double speed(Player driver, Afloat a) {
        Optional<ShipDef> def = fleet.def(a.row.type());
        double base = def.map(d -> ShipMath.baseSpeed(d, a.row.upgrades())).orElse(1.0);
        double storm = 1;
        Optional<SeaEventDef.Storm> s = storm(WorldKit.loc(a.boat));
        if (s.isPresent() && kit.stat(driver, "feature_storm_sailing", 0) < 1) {
            storm = s.get().speedMult();
        }
        return ShipMath.speed(base, kit.stat(driver, "boat_speed", 1.0), storm);
    }

    private Optional<SeaEventDef.Storm> storm(Location at) {
        SeaEventService events = seaEvents;
        if (events == null) {
            return Optional.empty();
        }
        return events.stormAt(at)
                .flatMap(e -> kit.api().content().snapshot().get(SeaEventDef.TYPE, e.eventId()))
                .map(SeaEventDef::storm);
    }

    // ------------------------------------------------------------------ tick

    void tick() {
        double accel = kit.balance().boatAcceleration();
        for (Afloat a : fleet.afloatList()) {
            if (!a.boat.isValid()) {
                var _ =
                        fleet.recall(
                                a,
                                a.row.state() == ShipState.WRECKED
                                        ? ShipState.WRECKED
                                        : ShipState.STORED);
                continue;
            }
            a.ticks++;
            RigInstance rig = a.rig;
            if (rig != null && a.follow) {
                Location at = WorldKit.loc(a.boat);
                rig.moveTo(at, 1);
                rig.setHeading(at.getYaw(), 0);
            }
            List<Entity> passengers = a.boat.getPassengers();
            if (passengers.isEmpty() || !(passengers.getFirst() instanceof Player driver)) {
                engineSound(a, false);
                continue;
            }
            if (!crew(a, driver)) {
                // The owner got off and a remaining passenger moved into the driver's seat: nobody
                // may drive
                // someone else's boat
                a.boat.removePassenger(driver);
                kit.actionBar(driver, "world.ship.not_crew");
                engineSound(a, false);
                continue;
            }
            Vector velocity = a.boat.getVelocity();
            engineSound(a, Math.hypot(velocity.getX(), velocity.getZ()) >= ENGINE_MIN_SPEED);
            if (driver.getCurrentInput().isForward()) {
                double mult = speed(driver, a);
                double impulse = ShipMath.impulse(accel, mult);
                Vector v = a.boat.getVelocity();
                double h = Math.hypot(v.getX(), v.getZ());
                if (Math.abs(impulse) > 1e-4 && (impulse > 0 || h > Math.abs(impulse))) {
                    Location at = WorldKit.loc(a.boat);
                    Vector forward = at.getDirection().setY(0);
                    if (forward.lengthSquared() > 1e-6) {
                        a.boat.setVelocity(v.add(forward.normalize().multiply(impulse)));
                    }
                }
            }
            if (a.ticks % 2 == 0) {
                breakIce(a);
            }
        }
        long now = System.currentTimeMillis();
        while (!melted.isEmpty() && melted.peek().restoreAt() <= now) {
            Melt m = melted.poll();
            Block block = m.at().getBlock();
            if (block.getType() == Material.WATER && !hasBoatAt(m.at())) {
                block.setType(m.type());
            } else {
                melted.add(new Melt(m.at(), m.type(), now + 5_000));
            }
        }
    }

    /**
     * Turns the looping sailing sound on and off. It follows the boat and is heard by nearby
     * players.
     */
    private void engineSound(Afloat a, boolean on) {
        if (on && !a.engine.active()) {
            Optional<ShipDef> def = fleet.def(a.row.type());
            String key = def.map(ShipDef::engineSound).orElse(ShipDef.ENGINE_LOOP);
            float pitch = (float) def.map(ShipDef::enginePitch).orElse(1.0).doubleValue();
            a.engine =
                    HarborSounds.loopFrom(
                            kit.tasks(),
                            a.boat,
                            key,
                            HarborSounds.Options.DEFAULT
                                    .withSource(Sound.Source.NEUTRAL)
                                    .withPitch(pitch));
        } else if (!on && a.engine.active()) {
            a.engine.stop();
            a.engine = HarborSounds.Loop.NONE;
        }
    }

    private boolean hasBoatAt(Location at) {
        World world = at.getWorld();
        return world != null
                && !world.getNearbyEntitiesByType(Boat.class, at.clone().add(0.5, 0.5, 0.5), 1.5)
                        .isEmpty();
    }

    private void breakIce(Afloat a) {
        Optional<ShipDef> def = fleet.def(a.row.type());
        if (def.isEmpty() || !def.get().has(ShipFlag.ICE_BREAK)) {
            return;
        }
        Location at = WorldKit.loc(a.boat);
        Optional<WorldIndex.Entry> region = regions.entryAt(at);
        if (region.isEmpty()) {
            return;
        }
        String iceArea = region.get().ext().iceArea();
        if (iceArea == null) {
            return;
        }
        Vector forward = at.getDirection().setY(0);
        if (forward.lengthSquared() < 1e-6) {
            return;
        }
        forward.normalize();
        Vector side = new Vector(-forward.getZ(), 0, forward.getX());
        long restore = System.currentTimeMillis() + kit.balance().iceRestoreSeconds() * 1000L;
        World world = a.boat.getWorld();
        for (double ahead = 1.0; ahead <= 2.0; ahead += 1.0) {
            for (int s = -1; s <= 1; s++) {
                Location probe =
                        at.clone()
                                .add(forward.clone().multiply(ahead))
                                .add(side.clone().multiply(s));
                if (!region.get().inArea(iceArea, probe.getX(), probe.getY(), probe.getZ())) {
                    continue;
                }
                for (int dy = -1; dy <= 0; dy++) {
                    Block block =
                            world.getBlockAt(
                                    probe.getBlockX(), probe.getBlockY() + dy, probe.getBlockZ());
                    if (ICE.contains(block.getType())) {
                        Material type = block.getType();
                        block.setType(Material.WATER);
                        melted.add(new Melt(block.getLocation(), type, restore));
                    }
                }
            }
        }
    }

    /** Every second: storm durability damage. */
    void slowTick() {
        for (Afloat a : fleet.afloatList()) {
            if (!a.boat.isValid()) {
                continue;
            }
            Optional<SeaEventDef.Storm> storm = storm(WorldKit.loc(a.boat));
            if (storm.isEmpty() || a.ticks / 20 % Math.max(1, storm.get().intervalSeconds()) != 0) {
                continue;
            }
            Player owner = Bukkit.getPlayer(a.row.owner());
            double mult = owner == null ? 1.0 : kit.stat(owner, "boat_damage", 1.0);
            a.damage += ShipMath.stormDamage(storm.get().damage(), mult);
            int whole = (int) Math.floor(a.damage);
            if (whole <= 0) {
                continue;
            }
            a.damage -= whole;
            HarborSounds.playAt(WorldKit.loc(a.boat), HULL_HIT);
            int next = Math.max(0, a.row.durability() - whole);
            a.row = a.row.withDurability(next, ShipState.AFLOAT);
            fleet.replace(a.row);
            if (next <= 0) {
                wreck(a);
            } else if (owner != null && a.boat.getPassengers().contains(owner)) {
                kit.actionBar(
                        owner,
                        "world.ship.storm_damage",
                        Placeholder.unparsed("durability", String.valueOf(next)),
                        Placeholder.unparsed(
                                "max", String.valueOf(fleet.info(a.row).maxDurability())));
            }
        }
    }

    private void wreck(Afloat a) {
        for (Entity p : a.boat.getPassengers()) {
            if (p instanceof Player player) {
                kit.send(player, "world.ship.wrecked");
            }
        }
        var _ = fleet.recall(a, ShipState.WRECKED);
        Player owner = Bukkit.getPlayer(a.row.owner());
        if (owner != null) {
            Bukkit.getPluginManager()
                    .callEvent(
                            new ShipWreckedEvent(
                                    owner, fleet.info(a.row.withDurability(0, ShipState.WRECKED))));
        }
    }

    // ------------------------------------------------------------------ events

    /** Blocks entry into storm waters (unless it is an ocean explorer). */
    void onVehicleMove(VehicleMoveEvent event) {
        Long id = fleet.shipIdOf(event.getVehicle().getUniqueId());
        if (id == null) {
            return;
        }
        Afloat a = fleet.afloat(id);
        if (a == null || fleet.def(a.row.type()).map(d -> d.has(ShipFlag.STORM)).orElse(false)) {
            return;
        }
        if (storm(event.getTo()).isPresent() && storm(event.getFrom()).isEmpty()) {
            Location back = event.getFrom().clone();
            var _ = a.boat.teleport(back);
            var _ = kit.tasks().later(1, () -> a.boat.setVelocity(new Vector()));
            for (Entity p : a.boat.getPassengers()) {
                if (p instanceof Player player) {
                    kit.actionBar(player, "world.ship.storm_blocked");
                }
            }
        }
    }

    /** The owner or a member of the owner's party (owner only without a party implementation). */
    private boolean crew(Afloat a, Player player) {
        UUID owner = a.row.owner();
        UUID uuid = player.getUniqueId();
        return owner.equals(uuid)
                || kit.api().parties().map(p -> p.sameParty(owner, uuid)).orElse(false);
    }

    /**
     * The driver's seat (first player passenger) is for the owner and party members only. Once a
     * crew member is aboard, anyone may take the remaining seats.
     */
    void onBoard(VehicleEnterEvent event) {
        Long id = fleet.shipIdOf(event.getVehicle().getUniqueId());
        if (id == null || !(event.getEntered() instanceof Player player)) {
            return;
        }
        Afloat a = fleet.afloat(id);
        if (a == null || crew(a, player)) {
            return;
        }
        boolean driverSeatFree =
                a.boat.getPassengers().stream().noneMatch(e -> e instanceof Player);
        if (driverSeatFree) {
            event.setCancelled(true);
            kit.actionBar(player, "world.ship.not_crew");
        }
    }

    void onEnter(VehicleEnterEvent event) {
        Long id = fleet.shipIdOf(event.getVehicle().getUniqueId());
        if (id == null || !(event.getEntered() instanceof Player)) {
            return;
        }
        Afloat a = fleet.afloat(id);
        RigInstance rig = a == null ? null : a.rig;
        if (a == null || rig == null || a.follow) {
            return;
        }
        // The first passenger is the driver, so dismount the decoration and remount it into the
        // second
        // seat
        rig.dismount();
        var _ =
                kit.tasks()
                        .later(
                                1,
                                () -> {
                                    if (rig.isValid() && a.boat.isValid()) {
                                        rig.mount(a.boat);
                                    }
                                });
    }
}
