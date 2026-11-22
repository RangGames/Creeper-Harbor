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
 * Environment debuffs (docs 20 8.4 'environment debuffs', all numbers are proposed values).
 * Computed every 20 ticks only for players inside an environment zone.
 *
 * <ul>
 *   <li>Frostbite (Frost Fjord): +1/s outdoors, +3/s in water, −2/s within 4 blocks of a heat
 *       source or indoors. Slowness I at 60 or more, damage plus the frozen screen every 3 seconds
 *       at 100. Immune with the winter coat. Rise multiplier = env_rate × cold_debuff_time (fjord
 *       fish soup −50%) × debuff_duration (exploration tier 3 −20%)
 *   <li>Oxygen (Ashen Trench depths): −1/s from 20 blocks below the surface (120 seconds with a
 *       diving helmet, ×2 without), ×0.5 riding with nautilus armor, recovers in air pockets and at
 *       the surface. Red border at 20% or less (WorldBorderService WORLD_ENVIRONMENT), 2 damage per
 *       second at 0
 *   <li>Fire (Lava Lake): vanilla fire damage, heat-resistant boots −50%. Immunity while fishing is
 *       handled by Fishing
 * </ul>
 *
 * Damage is multiplied by the debuff_damage stat (environment adaptation −15%/rank). Gauges live in
 * a PlayerComponent so they carry over after a server move.
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
                            // Vanilla fire damage + onDamage modifier
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
            return; // Fishing uses the action bar while fishing
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

    /** Lava Lake: heat-resistant boots −50% fire damage, debuff_damage modifier. */
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
