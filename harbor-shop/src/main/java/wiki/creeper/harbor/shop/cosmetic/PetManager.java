package wiki.creeper.harbor.shop.cosmetic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.event.CosmeticEquipEvent;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.content.ShopSettings;
import wiki.creeper.harbor.shop.util.Locs;

/**
 * 동반 펫·선박 장식·찌 효과 (docs 20 12.4 '펫·선박 스킨', 12.6). 펫은 Core 리그로 플레이어당 1마리, 32블록 안 플레이어에게만 보이고(밖은 갱신
 * 생략), 숨김 조건(보스 경기장 등)에서는 치운다. 선박 장식은 플레이어가 보트에 타면 리그를 보트에 태워 모두에게 보여 준다. 찌 효과는 찌가 물에 있는 동안 파티클을
 * 뿌린다. 메인 스레드 전용.
 */
public final class PetManager implements Listener {

    private final ShopRuntime rt;
    private final CosmeticManager cosmetics;
    private final Map<UUID, RigInstance> pets = new HashMap<>();
    private final Map<UUID, RigInstance> boats = new HashMap<>();
    private long tick;

    public PetManager(ShopRuntime rt, CosmeticManager cosmetics) {
        this.rt = rt;
        this.cosmetics = cosmetics;
    }

    public void start() {
        var _ = rt.tasks().timer(20, 1, this::tick);
    }

    public void stop() {
        pets.values().forEach(RigInstance::remove);
        boats.values().forEach(RigInstance::remove);
        pets.clear();
        boats.clear();
    }

    private void tick() {
        tick++;
        ShopSettings.Pet cfg = rt.settings().pet();
        if (tick % cfg.updateTicks() == 0) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                updatePet(player, cfg);
            }
        }
        if (tick % 4 == 0) {
            lineFx();
        }
    }

    // ------------------------------------------------------------------ 펫

    private @Nullable CosmeticDef equippedDef(Player player, CosmeticDef.Category slot) {
        HarborId id = rt.profile(player.getUniqueId()).equipped().get(slot);
        return id == null ? null : rt.cosmetic(id).orElse(null);
    }

    private void updatePet(Player player, ShopSettings.Pet cfg) {
        UUID uuid = player.getUniqueId();
        CosmeticDef def = equippedDef(player, CosmeticDef.Category.PET);
        RigId rig = def == null ? null : def.rig();
        RigInstance pet = pets.get(uuid);
        boolean hide =
                rig == null
                        || player.isDead()
                        || player.getGameMode() == GameMode.SPECTATOR
                        || cosmetics.suppressed(player);
        if (hide || rig == null) {
            if (pet != null) {
                pet.remove();
                pets.remove(uuid);
            }
            return;
        }
        if (pet != null
                && (!pet.isValid()
                        || !pet.rig().equals(rig)
                        || !pet.location().getWorld().equals(player.getWorld()))) {
            pet.remove();
            pets.remove(uuid);
            pet = null;
        }
        Location target = followPoint(player, cfg.followDistance());
        if (pet == null) {
            try {
                pet =
                        rt.api()
                                .rigs()
                                .spawn(rig, target, RigOptions.privateTo(viewers(player, cfg)));
            } catch (IllegalArgumentException e) {
                rt.log().warn("펫 리그가 없습니다: {}", rig);
                return;
            }
            if (pet.entities().size() > cfg.maxParts()) {
                rt.log().warn("펫 리그 부품이 {}개를 넘습니다: {}", cfg.maxParts(), rig);
                pet.remove();
                return;
            }
            pets.put(uuid, pet);
            return;
        }
        List<Player> viewers = viewers(player, cfg);
        if (tick % 20 == 0) {
            pet.setViewers(viewers);
        }
        if (viewers.isEmpty()) {
            return; // 볼 사람이 없으면 이동 생략 (LOD)
        }
        if (pet.location().distanceSquared(target) > 0.04) {
            pet.moveTo(target);
            pet.setHeading(Locs.of(player).getYaw(), 0);
        }
    }

    private static Location followPoint(Player player, double distance) {
        Location base = Locs.of(player);
        Vector back = base.getDirection().setY(0);
        if (back.lengthSquared() < 1e-6) {
            back = new Vector(0, 0, 1);
        }
        back.normalize().multiply(-distance);
        Vector side = new Vector(-back.getZ(), 0, back.getX()).normalize().multiply(0.6);
        Location at = base.clone().add(back).add(side).add(0, 0.4, 0);
        at.setYaw(base.getYaw());
        at.setPitch(0);
        return at;
    }

    private static List<Player> viewers(Player owner, ShopSettings.Pet cfg) {
        double max = (double) cfg.viewDistance() * cfg.viewDistance();
        List<Player> list = new ArrayList<>();
        for (Player p : owner.getWorld().getPlayers()) {
            if (Locs.of(p).distanceSquared(Locs.of(owner)) <= max) {
                list.add(p);
            }
        }
        return list;
    }

    // ------------------------------------------------------------------ 선박 장식

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnter(VehicleEnterEvent event) {
        if (!(event.getVehicle() instanceof Boat boat)
                || !(event.getEntered() instanceof Player player)
                || boats.containsKey(boat.getUniqueId())) {
            return;
        }
        CosmeticDef def = equippedDef(player, CosmeticDef.Category.BOAT_SKIN);
        RigId rigId = def == null ? null : def.rig();
        if (rigId == null || cosmetics.suppressed(player)) {
            return;
        }
        try {
            RigInstance rig = rt.api().rigs().spawn(rigId, Locs.of(boat));
            rig.mount(boat);
            boats.put(boat.getUniqueId(), rig);
        } catch (IllegalArgumentException e) {
            rt.log().warn("선박 장식 리그가 없습니다: {}", rigId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExit(VehicleExitEvent event) {
        if (event.getVehicle() instanceof Boat boat && boat.getPassengers().size() <= 1) {
            removeBoat(boat.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDestroy(VehicleDestroyEvent event) {
        removeBoat(event.getVehicle().getUniqueId());
    }

    private void removeBoat(UUID boat) {
        RigInstance rig = boats.remove(boat);
        if (rig != null) {
            rig.remove();
        }
    }

    // ------------------------------------------------------------------ 찌 효과

    private void lineFx() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            FishHook hook = player.getFishHook();
            if (hook == null) {
                continue;
            }
            CosmeticDef def = equippedDef(player, CosmeticDef.Category.LINE_FX);
            String particle = def == null ? null : def.particle();
            if (def == null || particle == null) {
                continue;
            }
            Fx.spawn(particle, def.particleColor(), Locs.of(hook).add(0, 0.2, 0), 2, 0.05, 32);
        }
    }

    // ------------------------------------------------------------------ 수명

    @EventHandler
    public void onEquip(CosmeticEquipEvent event) {
        if (event.slot() == CosmeticDef.Category.PET) {
            RigInstance pet = pets.remove(event.getPlayer().getUniqueId());
            if (pet != null) {
                pet.remove();
            }
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        RigInstance pet = pets.remove(event.getPlayer().getUniqueId());
        if (pet != null) {
            pet.remove();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        RigInstance pet = pets.remove(event.getPlayer().getUniqueId());
        if (pet != null) {
            pet.remove();
        }
        if (event.getPlayer().getVehicle() instanceof Boat boat) {
            removeBoat(boat.getUniqueId());
        }
    }
}
