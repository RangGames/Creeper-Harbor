package wiki.creeper.harbor.fishing.session;

import static wiki.creeper.harbor.fishing.session.FishingManager.loc;

import io.papermc.paper.world.MoonPhase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.fishing.api.CollectionProvider;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.content.FishingPresets;
import wiki.creeper.harbor.fishing.rod.RodParts;
import wiki.creeper.harbor.fishing.roll.GradeBonus;
import wiki.creeper.harbor.fishing.roll.RollContext;
import wiki.creeper.harbor.fishing.roll.RollModifiers;
import wiki.creeper.harbor.fishing.sea.SeaEventBook;

/**
 * Fishing roll inputs (region key, time/weather/moon, roll modifiers, rod and bait values). Holds
 * the collection and region key implementations registered with FishingManager. Main thread only.
 */
final class CastContext {

    private static final double BASE_WARN_SECONDS = 0.6;

    private final HarborApi api;
    private final BaitStacks baits;
    private final Logger log;
    private final SeaEventBook seaEvents;
    private final RodParts rods;
    private @Nullable CollectionProvider collection;
    private @Nullable RegionKeyResolver regionResolver;

    CastContext(
            HarborApi api, BaitStacks baits, SeaEventBook seaEvents, RodParts rods, Logger log) {
        this.api = api;
        this.baits = baits;
        this.seaEvents = seaEvents;
        this.rods = rods;
        this.log = log;
    }

    RodParts rods() {
        return rods;
    }

    void registerCollection(CollectionProvider provider) {
        this.collection = provider;
    }

    void registerRegionResolver(RegionKeyResolver resolver) {
        this.regionResolver = resolver;
    }

    BaitStacks baits() {
        return baits;
    }

    @Nullable CollectionProvider collection() {
        return collection;
    }

    FishingBalance balance() {
        return new FishingBalance(api.content().snapshot().single(Balance.TYPE));
    }

    FishingPresets presets() {
        return api.content().snapshot().single(FishingPresets.TYPE);
    }

    /**
     * Region of a location (regions.yml bounds). World names can be mapped to harbor_main etc. via
     * balance.yml world_alias (for development servers).
     */
    Optional<RegionDef> regionAt(Location loc) {
        World world = loc.getWorld();
        if (world == null) {
            return Optional.empty();
        }
        String worldId =
                api.content()
                        .snapshot()
                        .single(Balance.TYPE)
                        .string("world_alias." + world.getName())
                        .orElse(world.getName());
        return api.content().snapshot().all(RegionDef.TYPE).values().stream()
                .filter(r -> r.world().equals(worldId) && r.contains(loc.getX(), loc.getZ()))
                .findFirst();
    }

    /**
     * Region key at the bobber location. The RegionKeyResolver registered by World first, otherwise
     * regions.yml.
     */
    Optional<RegionKeyResolver.RegionKey> regionKey(Location bobber) {
        RegionKeyResolver resolver = regionResolver;
        if (resolver != null) {
            try {
                return resolver.resolve(bobber);
            } catch (RuntimeException e) {
                log.warn("RegionKeyResolver 오류: {}", String.valueOf(e));
            }
        }
        return regionAt(bobber)
                .map(
                        r ->
                                new RegionKeyResolver.RegionKey(
                                        r.id(), r.biome(), List.of(), false, null));
    }

    boolean areasKnown() {
        return regionResolver != null;
    }

    RollContext context(Player player, RegionKeyResolver.RegionKey key, @Nullable ItemId bait) {
        World world = player.getWorld();
        String timeKey = timeKey(world);
        String weather = world.isThundering() ? "thunder" : world.hasStorm() ? "rain" : "clear";
        MoonPhase phase = world.getMoonPhase();
        String moon =
                phase == MoonPhase.FULL_MOON ? "full" : phase == MoonPhase.NEW_MOON ? "new" : "";
        HarborPlayer hp = api.players().get(player);
        CollectionProvider provider = collection;
        UUID uuid = player.getUniqueId();
        ContentSnapshot snapshot = api.content().snapshot();
        return new RollContext(
                key.region(),
                timeKey,
                weather,
                moon,
                bait,
                hp.jobTier().level(),
                flag -> api.flags().hasServer(flag),
                fish -> provider != null && provider.hasCaught(uuid, fish),
                key.areas(),
                areasKnown(),
                seaEvents.mythicWindows(
                        key.region(),
                        Instant.now(),
                        id ->
                                HarborId.tryParse(id)
                                        .flatMap(h -> snapshot.get(FishDef.TYPE, h))
                                        .filter(f -> f.grade() == Grade.MYTHIC)),
                flag -> api.flags().hasPlayer(uuid, flag),
                equipmentTags(player));
    }

    /** Time key of a world: dawn · day · dusk · night. */
    static String timeKey(World world) {
        long time = world.getTime();
        return time >= 23000 || time < 1000
                ? "dawn"
                : time < 12000 ? "day" : time < 13800 ? "dusk" : "night";
    }

    /**
     * Tags of the worn armour for species extra equipment_tag (proposed): the material family of
     * each piece (gold for golden, iron, diamond, netherite, leather, chainmail, copper …).
     */
    static Set<String> equipmentTags(Player player) {
        Set<String> tags = new HashSet<>();
        for (ItemStack piece : player.getInventory().getArmorContents()) {
            if (piece == null || piece.isEmpty()) {
                continue;
            }
            String name = piece.getType().name().toLowerCase(Locale.ROOT);
            int cut = name.indexOf('_');
            if (cut > 0) {
                String family = name.substring(0, cut);
                tags.add(family.equals("golden") ? "gold" : family);
            }
        }
        return tags;
    }

    /**
     * Cast-level condition tokens at the player (time key, region name) for conditional gear stats
     * such as {@code bite_wait.night} (glow float).
     */
    Set<String> castTokens(Player player) {
        Set<String> tokens = new HashSet<>();
        tokens.add(timeKey(player.getWorld()));
        regionAt(loc(player)).ifPresent(r -> tokens.add(r.id().id().name()));
        return tokens;
    }

    /** Roll modifiers (category product, "7. Balance design" 4.2). */
    RollModifiers modifiers(UUID uuid, Map<String, Double> gearStats, RegionId region) {
        GradeBonus.Sum rare = categorySum(uuid, "rare_weight");
        rare.add(GradeBonus.Category.BAIT, gearStats.getOrDefault("rare_weight", 0.0));
        GradeBonus.Sum epic = categorySum(uuid, "rare_weight");
        epic.add(GradeBonus.Category.BAIT, gearStats.getOrDefault("rare_weight", 0.0));
        for (Modifier m : applied(uuid, "epic_weight")) {
            epic.add(m.source(), addValue(m));
        }
        epic.add(GradeBonus.Category.BAIT, gearStats.getOrDefault("epic_weight", 0.0));
        // Tide reading (explore 3rd tier): rare-or-better bonus in event waters (proposed key
        // event_spawn_bonus, job category)
        if (seaEvents.covers(region, Instant.now())) {
            double event = stat(uuid, "event_spawn_bonus");
            rare.add(GradeBonus.Category.JOB, event);
            epic.add(GradeBonus.Category.JOB, event);
        }
        double cap = balance().rareBonusCap();
        Map<Grade, Double> bonus = new java.util.EnumMap<>(Grade.class);
        bonus.put(Grade.RARE, rare.capped(cap));
        bonus.put(Grade.EPIC, epic.capped(cap));
        List<RollModifiers.WeightBonus> weightBonus = new ArrayList<>();
        gearStats.forEach(
                (k, v) -> RollModifiers.WeightBonus.parse(k, v).ifPresent(weightBonus::add));
        return new RollModifiers(
                bonus,
                stat(uuid, "uncaught_weight"),
                (int) Math.round(stat(uuid, "legend_pity_start")),
                stat(uuid, "legend_chance") * (1 + gearStats.getOrDefault("legend_chance", 0.0)),
                seaEvents.speciesMult(region, Instant.now()),
                stat(uuid, "mythic_chance"),
                weightBonus);
    }

    private GradeBonus.Sum categorySum(UUID uuid, String key) {
        GradeBonus.Sum sum = new GradeBonus.Sum();
        for (Modifier m : applied(uuid, key)) {
            sum.add(m.source(), addValue(m));
        }
        return sum;
    }

    private List<Modifier> applied(UUID uuid, String key) {
        return api.modifiers().explain(uuid, StatKey.of(key), 0).applied();
    }

    private static double addValue(Modifier m) {
        return m.op() == Modifier.Op.ADD ? m.value() : m.value() - 1;
    }

    double stat(UUID uuid, String key) {
        return api.modifiers().value(uuid, StatKey.of(key));
    }

    /** Bait the cast uses: off hand first, else the bait chosen with /미끼 ({@link BaitStacks}). */
    @Nullable ItemId equippedBait(Player player) {
        BaitStacks.Stack stack = baits.current(player);
        return stack == null ? null : stack.def().id();
    }

    Optional<ItemDef> itemDef(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Plain)
                .map(i -> (HarborItem.Plain) i)
                .flatMap(p -> api.content().snapshot().get(ItemDef.TYPE, p.id().id()));
    }

    /**
     * Sum of the stats of the rod (main hand) with its attached parts and rod-only enchants, and
     * the bait (off hand or chosen with /미끼).
     */
    Map<String, Double> gearStats(Player player) {
        Map<String, Double> sum = new HashMap<>();
        ItemStack rod = player.getInventory().getItemInMainHand();
        itemDef(rod).ifPresent(d -> d.stats().forEach((k, v) -> sum.merge(k, v, Double::sum)));
        rods.stats(rod).forEach((k, v) -> sum.merge(k, v, Double::sum));
        BaitStacks.Stack bait = baits.current(player);
        if (bait != null) {
            bait.def().stats().forEach((k, v) -> sum.merge(k, v, Double::sum));
        }
        return sum;
    }

    Minigame.Gear gear(Player player, double extraLine, boolean longCast) {
        UUID uuid = player.getUniqueId();
        Map<String, Double> gear = gearStats(player);
        FishingBalance b = balance();
        double warn =
                BASE_WARN_SECONDS
                        + stat(uuid, "pattern_warn")
                        + (player.getPing() >= 150 ? b.pingCompensationSeconds() : 0);
        double safe =
                stat(uuid, "tension_safe_zone")
                        + gear.getOrDefault("tension_safe_zone", 0.0)
                        + (longCast ? stat(uuid, "tension_safe_zone_long_cast") : 0);
        return new Minigame.Gear(
                stat(uuid, "line_strength") + gear.getOrDefault("line_strength", 0.0) + extraLine,
                stat(uuid, "reel_speed") + gear.getOrDefault("reel_speed", 0.0),
                safe,
                gear.getOrDefault("stability", 0.0),
                (int) Math.round(warn * 20));
    }
}
