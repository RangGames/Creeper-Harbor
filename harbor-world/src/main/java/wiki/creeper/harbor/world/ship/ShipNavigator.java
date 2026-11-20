package wiki.creeper.harbor.world.ship;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * 항해. 틱마다 속도 보정·장식 따라가기·항해 소리·쇄빙(녹인 얼음 되돌리기), 1초마다 폭풍 내구도 피해와 파손, 폭풍 해역 진입 차단, 탑승 때 장식 자리 맞추기를 맡는다.
 * 메인 스레드 전용.
 */
final class ShipNavigator {

    private static final Set<Material> ICE =
            Set.of(Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    // 선박 소리 ('15. 사운드' 4.4)
    private static final String HULL_HIT = "harbor:ship.hull_hit";

    /** 이 속도(블록/틱) 이상으로 움직일 때만 항해 소리를 낸다 (제안값). */
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

    /** 서버 종료: 녹인 얼음을 모두 되돌린다. */
    void restoreAll() {
        while (!melted.isEmpty()) {
            Melt m = melted.poll();
            m.at().getBlock().setType(m.type());
        }
    }

    // ------------------------------------------------------------------ 속도·폭풍

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

    // ------------------------------------------------------------------ 틱

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

    /** 항해 반복 소리를 켜고 끈다. 배를 따라가며 근처 플레이어에게 들린다. */
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

    /** 1초마다: 폭풍 내구도 피해. */
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

    // ------------------------------------------------------------------ 이벤트

    /** 폭풍 해역 진입 차단 (원양 탐사선이 아니면). */
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
        // 첫 승객이 조종자라서 장식을 내렸다가 다시 태워 두 번째 자리로 보낸다
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
