package wiki.creeper.harbor.world.env;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.AbstractNautilus;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.WorldBorderService;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.data.WorldState;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * 환경 디버프 (docs 20 8.4 '환경 디버프', 수치는 모두 제안값). 환경 구역 안 플레이어만 20틱마다 계산한다.
 *
 * <ul>
 *   <li>혹한 (서리 협만): 야외 +1/초, 물속 +3/초, 열원 4블록 안·실내 −2/초. 60 이상 구속 I, 100이면 3초마다 피해 + 얼어붙음 화면. 방한 외투
 *       면역. 상승 배율 = env_rate × cold_debuff_time(협만 생선 수프 −50%) × debuff_duration(탐험 3차 −20%)
 *   <li>산소 (잿빛 해구 심층): 수면 20블록 아래에서 −1/초 (잠수 헬멧 기준 120초, 없으면 ×2), 노틸러스 갑옷 탑승 ×0.5, 공기 주머니·수면에서 회복.
 *       20% 이하 붉은 테두리(WorldBorderService WORLD_ENVIRONMENT), 0이면 초당 피해 2
 *   <li>화염 (용암 호수): 바닐라 화염 피해, 내열 장화 −50%. 낚시 중 면역은 Fishing이 처리
 * </ul>
 *
 * 피해는 debuff_damage 스탯(환경 적응 −15%/랭크)을 곱한다. 게이지는 PlayerComponent에 두어 서버 이동 뒤에도 이어진다.
 */
public final class EnvironmentManager implements Listener {

    private static final Set<Material> HEAT =
            Set.of(
                    Material.CAMPFIRE,
                    Material.SOUL_CAMPFIRE,
                    Material.FIRE,
                    Material.SOUL_FIRE,
                    Material.LAVA,
                    Material.MAGMA_BLOCK,
                    Material.FURNACE,
                    Material.BLAST_FURNACE,
                    Material.SMOKER,
                    Material.LANTERN,
                    Material.TORCH,
                    Material.WALL_TORCH);

    private static final ItemId COAT = ItemId.parse("item/gear/cold_coat");
    private static final ItemId HELMET = ItemId.parse("item/gear/diving_helmet");
    private static final ItemId BOOTS = ItemId.parse("item/gear/heat_boots");

    private final WorldKit kit;
    private final RegionManager regions;
    private final Map<UUID, Integer> coldTicks = new HashMap<>();
    private final Map<UUID, Boolean> warned = new HashMap<>();

    public EnvironmentManager(WorldKit kit, RegionManager regions) {
        this.kit = kit;
        this.regions = regions;
    }

    public void start() {
        var _ = kit.tasks().timer(20, 20, this::tick);
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Optional<WorldIndex.Entry> region = regions.currentEntry(uuid);
            Optional<HarborPlayer> hp = kit.api().players().get(uuid);
            if (hp.isEmpty()) {
                continue;
            }
            WorldState state = hp.get().get(RegionManager.COMPONENT);
            double cold = state.cold();
            double oxygen = state.oxygen();
            WorldBalance b = kit.balance();
            Location at = WorldKit.loc(player);
            boolean coldZone = false;
            boolean oxygenZone = false;
            if (region.isPresent()
                    && !player.isDead()
                    && (player.getGameMode() == GameMode.SURVIVAL
                            || player.getGameMode() == GameMode.ADVENTURE)) {
                WorldIndex.Entry e = region.get();
                for (RegionExt.Env env : e.ext().env()) {
                    if (!e.inArea(env.area(), at.getX(), at.getY(), at.getZ())) {
                        continue;
                    }
                    switch (env.type()) {
                        case COLD -> {
                            coldZone = true;
                            cold = cold(player, at, cold, b);
                        }
                        case OXYGEN -> {
                            oxygenZone = true;
                            oxygen = oxygen(player, at, env, oxygen, b);
                        }
                        case HEAT -> {
                            // 바닐라 화염 피해 + onDamage 보정
                        }
                    }
                }
            }
            if (!coldZone && cold > 0) {
                cold = Math.max(0, cold + b.coldWarm() * 2);
            }
            if (!oxygenZone && oxygen >= 0) {
                oxygen = -1;
            }
            if (cold != state.cold() || oxygen != state.oxygen()) {
                hp.get().set(RegionManager.COMPONENT, state.withGauges(cold, oxygen));
            }
            border(
                    player,
                    (coldZone && cold >= b.coldMax())
                            || (oxygenZone
                                    && oxygen >= 0
                                    && oxygen <= b.oxygenMax() * b.oxygenWarnRatio()));
            showGauge(player, coldZone ? cold : -1, oxygenZone ? oxygen : -1, b);
        }
    }

    private double cold(Player player, Location at, double gauge, WorldBalance b) {
        if (kit.wearing(player, COAT)) {
            return Math.max(0, gauge + b.coldWarm());
        }
        Block block = at.getBlock();
        double delta;
        if (nearHeat(at, b.coldHeatRadius()) || block.getLightFromSky() < 4) {
            delta = b.coldWarm();
        } else if (player.isInWater()) {
            delta = b.coldWater();
        } else {
            delta = b.coldOutdoor();
        }
        if (delta > 0) {
            delta *=
                    kit.stat(player, "env_rate", 1.0)
                            * kit.stat(player, "cold_debuff_time", 1.0)
                            * kit.stat(player, "debuff_duration", 1.0);
        }
        double next = Math.max(0, Math.min(b.coldMax(), gauge + delta));
        UUID uuid = player.getUniqueId();
        if (next >= b.coldSlowAt()) {
            player.addPotionEffect(
                    new PotionEffect(PotionEffectType.SLOWNESS, 40, 0, true, false, true));
        }
        if (next >= b.coldMax()) {
            player.setFreezeTicks(player.getMaxFreezeTicks());
            int ticks = coldTicks.merge(uuid, 1, Integer::sum);
            if (ticks % Math.max(1, b.coldDamageIntervalSeconds()) == 0) {
                player.damage(b.coldDamage() * kit.stat(player, "debuff_damage", 1.0));
            }
        } else {
            coldTicks.remove(uuid);
        }
        return next;
    }

    private static boolean nearHeat(Location at, int radius) {
        World world = at.getWorld();
        if (world == null) {
            return false;
        }
        int bx = at.getBlockX();
        int by = at.getBlockY();
        int bz = at.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (HEAT.contains(world.getBlockAt(bx + dx, by + dy, bz + dz).getType())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private double oxygen(
            Player player, Location at, RegionExt.Env env, double gauge, WorldBalance b) {
        double max = b.oxygenMax();
        double current = gauge < 0 ? max : gauge;
        boolean deep = player.isInWater() && at.getY() < env.surfaceY() - env.depth();
        boolean pocket = false;
        Optional<WorldIndex.Entry> region = regions.currentEntry(player.getUniqueId());
        if (region.isPresent()) {
            double r2 = b.airPocketRadius() * b.airPocketRadius();
            for (RegionExt.Point p : region.get().ext().airPockets()) {
                double dx = p.x() - at.getX();
                double dy = p.y() - at.getY();
                double dz = p.z() - at.getZ();
                if (dx * dx + dy * dy + dz * dz <= r2) {
                    pocket = true;
                    break;
                }
            }
        }
        if (!deep || pocket) {
            return Math.min(max, current + b.oxygenRecover());
        }
        double use = 1;
        if (!kit.wearing(player, HELMET)) {
            use *= b.oxygenNoHelmetMult();
        }
        if (player.getVehicle() instanceof AbstractNautilus nautilus) {
            ItemStack armor = nautilus.getInventory().getArmor();
            if (armor != null && !armor.isEmpty()) {
                use *= b.oxygenNautilusMult();
            }
        }
        use *= kit.stat(player, "env_rate", 1.0) * kit.stat(player, "debuff_duration", 1.0);
        double next = Math.max(0, current - use);
        if (next <= 0) {
            player.damage(b.oxygenDamage() * kit.stat(player, "debuff_damage", 1.0));
        }
        return next;
    }

    private void border(Player player, boolean warn) {
        UUID uuid = player.getUniqueId();
        boolean was = warned.getOrDefault(uuid, false);
        if (warn && !was) {
            kit.api()
                    .worldBorders()
                    .request(
                            player,
                            WorldBorderService.Priority.WORLD_ENVIRONMENT,
                            WorldBorderService.Border.warning(WorldKit.loc(player)));
            warned.put(uuid, true);
        } else if (!warn && was) {
            kit.api().worldBorders().clear(player, WorldBorderService.Priority.WORLD_ENVIRONMENT);
            warned.remove(uuid);
        }
    }

    private void showGauge(Player player, double cold, double oxygen, WorldBalance b) {
        if (cold <= 0 && oxygen < 0) {
            return;
        }
        Optional<FishingService> fishing = kit.api().service(FishingService.class);
        if (fishing.isPresent() && fishing.get().isFishing(player.getUniqueId())) {
            return; // 낚시 중에는 Fishing이 액션바를 쓴다
        }
        if (cold > 0) {
            kit.actionBar(
                    player,
                    "world.env.cold_bar",
                    Placeholder.unparsed("bar", bar(cold / b.coldMax())),
                    Placeholder.unparsed("value", String.valueOf((int) cold)));
        } else {
            kit.actionBar(
                    player,
                    "world.env.oxygen_bar",
                    Placeholder.unparsed("bar", bar(oxygen / b.oxygenMax())),
                    Placeholder.unparsed("value", String.valueOf((int) oxygen)));
        }
    }

    static String bar(double ratio) {
        int cells = 10;
        int filled = (int) Math.round(Math.max(0, Math.min(1, ratio)) * cells);
        return "■".repeat(filled) + "□".repeat(cells - filled);
    }

    /** 용암 호수: 내열 장화 화염 피해 −50%, debuff_damage 보정. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        String cause = event.getCause().name();
        if (!cause.equals("FIRE")
                && !cause.equals("FIRE_TICK")
                && !cause.equals("LAVA")
                && !cause.equals("HOT_FLOOR")) {
            return;
        }
        Optional<WorldIndex.Entry> region = regions.currentEntry(player.getUniqueId());
        if (region.isEmpty() || !region.get().ext().hasEnv(RegionExt.EnvType.HEAT)) {
            return;
        }
        double mult = kit.stat(player, "debuff_damage", 1.0);
        if (kit.wearing(player, BOOTS)) {
            mult *= kit.balance().heatBootsMult();
        }
        event.setDamage(event.getDamage() * mult);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        coldTicks.remove(uuid);
        warned.remove(uuid);
    }
}
