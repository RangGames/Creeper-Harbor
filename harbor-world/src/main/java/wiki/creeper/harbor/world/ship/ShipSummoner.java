package wiki.creeper.harbor.world.ship;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ShipSkinProvider;
import wiki.creeper.harbor.world.api.ShipState;
import wiki.creeper.harbor.world.api.event.ShipSummonEvent;
import wiki.creeper.harbor.world.content.ShipDef;

/** 선박 소환. 재사용 대기시간, 근처 물 찾기, 보트 엔티티와 장식 리그(후원 등급 리그 포함) 띄우기를 맡는다. 메인 스레드 전용. */
final class ShipSummoner {

    // 선박 소리 ('15. 사운드' 4.4)
    private static final String BELL = "harbor:ship.bell";

    private final WorldKit kit;
    private final ShipFleet fleet;
    private final Map<UUID, Instant> cooldown = new HashMap<>();

    /** 후원 등급 캐시는 Shop이 Redis에 둔다. 메인 스레드에서 Redis를 부르지 않으려고 접속 때 읽어 둔 값을 쓴다. */
    private final Map<UUID, DonorTier> donors = new HashMap<>();

    ShipSummoner(WorldKit kit, ShipFleet fleet) {
        this.kit = kit;
        this.fleet = fleet;
    }

    void forgetCooldown(UUID uuid) {
        cooldown.remove(uuid);
    }

    void forgetDonor(UUID uuid) {
        donors.remove(uuid);
    }

    /**
     * 띄운다. shipId가 0 이하면 파손되지 않은 마지막 선박 (가장 늦게 산 배).
     *
     * @return 띄웠으면 true
     */
    boolean summon(Player player, long shipId) {
        UUID uuid = player.getUniqueId();
        Instant now = Instant.now();
        Instant ready = cooldown.get(uuid);
        if (ready != null && now.isBefore(ready)) {
            kit.send(
                    player,
                    "world.ship.cooldown",
                    Placeholder.unparsed(
                            "seconds",
                            String.valueOf(ready.getEpochSecond() - now.getEpochSecond() + 1)));
            return false;
        }
        List<ShipRepository.Row> mine = fleet.rows(uuid);
        Optional<ShipRepository.Row> pick =
                shipId > 0
                        ? mine.stream().filter(r -> r.shipId() == shipId).findFirst()
                        : mine.stream()
                                .filter(r -> r.state() != ShipState.WRECKED)
                                .reduce((a, b) -> b);
        if (pick.isEmpty()) {
            kit.send(player, "world.ship.none");
            return false;
        }
        ShipRepository.Row row = pick.get();
        if (row.state() == ShipState.WRECKED) {
            kit.send(player, "world.ship.wrecked_cannot");
            return false;
        }
        Optional<ShipDef> def = fleet.def(row.type());
        if (def.isEmpty()) {
            kit.send(player, "world.ship.none");
            return false;
        }
        Location water = findWater(WorldKit.loc(player), kit.balance().summonWaterRadius());
        if (water == null) {
            kit.send(player, "world.ship.no_water");
            return false;
        }
        ShipSummonEvent summonEvent = new ShipSummonEvent(player, fleet.info(row));
        Bukkit.getPluginManager().callEvent(summonEvent);
        if (summonEvent.isCancelled()) {
            return false;
        }
        for (Afloat a : fleet.afloatList()) {
            if (a.row.owner().equals(uuid)) {
                var _ = fleet.recall(a, ShipState.STORED);
            }
        }
        cooldown.put(uuid, now.plusSeconds(kit.balance().summonCooldownSeconds()));
        water.setYaw(WorldKit.loc(player).getYaw());
        EntityType type;
        try {
            type = EntityType.valueOf(def.get().entityName());
        } catch (IllegalArgumentException e) {
            type = def.get().chest() ? EntityType.OAK_CHEST_BOAT : EntityType.OAK_BOAT;
        }
        World world = water.getWorld();
        if (world == null || !(world.spawnEntity(water, type) instanceof Boat boat)) {
            kit.send(player, "world.ship.no_water");
            return false;
        }
        boat.setPersistent(false);
        boat.getPersistentDataContainer()
                .set(kit.key("ship"), PersistentDataType.LONG, row.shipId());
        RigInstance rig = spawnDecor(player, def.get(), boat);
        boolean follow = boat instanceof ChestBoat;
        if (rig != null && !follow) {
            rig.mount(boat);
        }
        Afloat a =
                new Afloat(
                        row.withDurability(row.durability(), ShipState.AFLOAT), boat, rig, follow);
        fleet.launch(a);
        fleet.replace(a.row);
        long id = row.shipId();
        int durability = row.durability();
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(
                                c ->
                                        ShipRepository.updateState(
                                                c, id, durability, ShipState.AFLOAT));
        HarborSounds.playAt(WorldKit.loc(boat), BELL); // 출항 종
        kit.send(player, "world.ship.summoned", Placeholder.unparsed("name", def.get().name()));
        return true;
    }

    private @Nullable RigInstance spawnDecor(Player owner, ShipDef def, Boat boat) {
        ShipSkinProvider skins = Bukkit.getServicesManager().load(ShipSkinProvider.class);
        if (skins != null && skins.hasBoatSkin(owner.getUniqueId())) {
            return null; // 선박 스킨은 Shop이 탑승 때 태운다
        }
        RigId rigId = def.decor();
        RigId donor = def.decorDonor();
        if (donor != null && DonorTier.FLEET.equals(donorTier(owner))) {
            rigId = donor;
        }
        if (rigId == null || !kit.api().rigs().ids().contains(rigId)) {
            return null;
        }
        try {
            return kit.api()
                    .rigs()
                    .spawn(rigId, WorldKit.loc(boat), RigOptions.publicRig().withMoveTicks(2));
        } catch (RuntimeException e) {
            kit.log().warn("선박 장식 리그를 띄우지 못했습니다 {}: {}", rigId, String.valueOf(e));
            return null;
        }
    }

    @Nullable DonorTier donorTier(Player player) {
        UUID uuid = player.getUniqueId();
        DonorTier cached = donors.get(uuid);
        if (cached == null) {
            var _ =
                    kit.tasks()
                            .supplyAsync(() -> DonorTier.lookup(kit.api().redis(), uuid))
                            .thenAcceptAsync(t -> donors.put(uuid, t), kit.tasks().main());
        }
        return cached;
    }

    private static @Nullable Location findWater(Location center, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return null;
        }
        int r = (int) Math.ceil(radius);
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    Block b =
                            world.getBlockAt(
                                    center.getBlockX() + dx,
                                    center.getBlockY() + dy,
                                    center.getBlockZ() + dz);
                    if (b.getType() != Material.WATER
                            || !b.getRelative(0, 1, 0).getType().isAir()) {
                        continue;
                    }
                    double d = dx * dx + dz * dz + dy * dy;
                    if (d < bestD && d <= radius * radius + 4) {
                        bestD = d;
                        best = b.getLocation().add(0.5, 1.0, 0.5);
                    }
                }
            }
        }
        return best;
    }
}
