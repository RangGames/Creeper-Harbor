package wiki.creeper.harbor.world.chart;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ChartService;
import wiki.creeper.harbor.world.api.FishingSpot;
import wiki.creeper.harbor.world.api.event.ChartProgressEvent;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.data.WorldState;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * Sea chart (docs 20 8.4 'sea chart'). When the chunk changes inside a region it is marked on the
 * bitmap (write-behind: PlayerComponent save); at 25 · 50 · 75 · 95% it pays gold (regional hourly
 * gold × 25% × (1 + chart master)) and at the last step 2 sea scales (ledger CHART_REWARD,
 * reference ID chart:s&lt;season&gt;:&lt;region&gt;:&lt;step&gt;). Map items share one MapView per
 * region, and a per-player renderer covers unvisited cells with fog (redrawn only when the bitmap
 * changed, at most once per second).
 */
public final class ChartManager implements ChartService, Listener {

    private static final Color FOG = new Color(70, 74, 82);
    private static final Color WATER = new Color(64, 128, 200);
    private static final Color LAND = new Color(176, 152, 110);

    private final WorldKit kit;
    private final RegionManager regions;
    private final Supplier<List<FishingSpot>> spots;
    private final Map<UUID, Map<RegionId, ChartBits>> cache = new HashMap<>();
    private final Map<UUID, Integer> versions = new HashMap<>();
    private final Map<RegionId, MapView> views = new HashMap<>();
    private final File mapFile;

    public ChartManager(WorldKit kit, RegionManager regions, Supplier<List<FishingSpot>> spots) {
        this.kit = kit;
        this.regions = regions;
        this.spots = spots;
        this.mapFile = new File(kit.plugin().getDataFolder(), "charts.yml");
    }

    /**
     * After a reload: the mask may have changed, so clear the cache (the next lookup recounts from
     * the DB original and the new mask).
     */
    public void clearCache() {
        cache.clear();
    }

    // ------------------------------------------------------------------ recording

    public ChartBits bits(UUID player, WorldIndex.Entry region) {
        return cache.computeIfAbsent(player, u -> new HashMap<>())
                .computeIfAbsent(
                        region.id(),
                        r -> {
                            WorldState.ChartRow row = regions.state(player).charts().get(r);
                            return row == null
                                    ? ChartBits.empty(region.mask())
                                    : ChartBits.of(region.mask(), row.bits(), row.claimed());
                        });
    }

    int version(UUID player) {
        return versions.getOrDefault(player, 0);
    }

    /** Chunk move inside a region (RegionManager hook). */
    public void onChunk(Player player, WorldIndex.Entry region, int cx, int cz) {
        ChartMask mask = region.mask();
        int cell = mask.cell(cx, cz);
        if (cell < 0) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ChartBits before = bits(uuid, region);
        ChartBits after = before.mark(mask, cell);
        if (after == before) {
            return;
        }
        store(player, region, after);
        int pctBefore = (int) Math.floor(before.ratio(mask) * 100);
        int pctAfter = (int) Math.floor(after.ratio(mask) * 100);
        if (pctAfter != pctBefore) {
            Bukkit.getPluginManager()
                    .callEvent(
                            new ChartProgressEvent(
                                    player, region.id(), after.ratio(mask) * 100, 0));
        }
        claimSteps(player, region);
    }

    private void store(Player player, WorldIndex.Entry region, ChartBits bits) {
        UUID uuid = player.getUniqueId();
        cache.computeIfAbsent(uuid, u -> new HashMap<>()).put(region.id(), bits);
        versions.merge(uuid, 1, Integer::sum);
        kit.api()
                .players()
                .get(uuid)
                .ifPresent(
                        hp ->
                                hp.set(
                                        RegionManager.COMPONENT,
                                        hp.get(RegionManager.COMPONENT)
                                                .withChart(
                                                        region.id(),
                                                        new WorldState.ChartRow(
                                                                bits.toBytes(),
                                                                bits.visited(),
                                                                bits.claimed()))));
    }

    /**
     * Pays the reward steps reached. The ledger reference ID is unique per season, region and step,
     * so nothing is paid twice.
     */
    private void claimSteps(Player player, WorldIndex.Entry region) {
        WorldBalance b = kit.balance();
        UUID uuid = player.getUniqueId();
        ChartBits bits = bits(uuid, region);
        double ratio = bits.ratio(region.mask());
        int steps = b.chartSteps();
        for (int step = 1; step <= steps; step++) {
            if (ratio < b.chartStep(step) || bits.isClaimed(step)) {
                continue;
            }
            bits = bits.withClaimed(bits.claimed() | (1 << (step - 1)));
            store(player, region, bits);
            long gold =
                    Math.round(
                            region.ext().hourlyGold()
                                    * b.chartStepGoldRatio()
                                    * (1 + kit.stat(player, "chart_reward_gold", 0)));
            String ref = "chart:s" + kit.season() + ":" + region.id().id().name() + ":" + step;
            int percent = (int) Math.round(b.chartStep(step) * 100);
            if (gold > 0) {
                var _ =
                        kit.api()
                                .currency()
                                .give(uuid, Currency.GOLD, gold, LedgerReason.CHART_REWARD, ref);
            }
            boolean last = step == steps;
            int mulbineul = last ? b.chartCompleteMulbineul() : 0;
            if (mulbineul > 0) {
                var _ =
                        kit.api()
                                .currency()
                                .give(
                                        uuid,
                                        Currency.MULBINEUL,
                                        mulbineul,
                                        LedgerReason.CHART_REWARD,
                                        ref);
            }
            kit.send(
                    player,
                    last ? "world.chart.complete" : "world.chart.step",
                    Placeholder.parsed("name", region.def().name()),
                    Placeholder.unparsed("percent", String.valueOf(percent)),
                    Placeholder.unparsed("reward", String.valueOf(gold)),
                    Placeholder.unparsed("mulbineul", String.valueOf(mulbineul)));
            Bukkit.getPluginManager()
                    .callEvent(new ChartProgressEvent(player, region.id(), ratio * 100, step));
        }
    }

    /** Admin command: sets completion to ratio (reward steps unchanged, paid on the next move). */
    public boolean adminSet(Player player, RegionId regionId, double ratio) {
        Optional<WorldIndex.Entry> region = kit.index().get(regionId);
        if (region.isEmpty()) {
            return false;
        }
        ChartBits current = bits(player.getUniqueId(), region.get());
        store(
                player,
                region.get(),
                ChartBits.filled(region.get().mask(), ratio, current.claimed()));
        claimSteps(player, region.get());
        return true;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cache.remove(event.getPlayer().getUniqueId());
        versions.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ ChartService

    @Override
    public Optional<ChartProgress> progress(UUID player, RegionId region) {
        return kit.index()
                .get(region)
                .filter(e -> kit.api().players().get(player).isPresent())
                .map(e -> progress(player, e));
    }

    private ChartProgress progress(UUID player, WorldIndex.Entry e) {
        ChartBits bits = bits(player, e);
        return new ChartProgress(e.id(), bits.visited(), e.mask().total(), bits.claimedSteps());
    }

    @Override
    public Map<RegionId, ChartProgress> progress(UUID player) {
        Map<RegionId, ChartProgress> result = new LinkedHashMap<>();
        if (kit.api().players().get(player).isEmpty()) {
            return result;
        }
        for (WorldIndex.Entry e : kit.index().regions()) {
            result.put(e.id(), progress(player, e));
        }
        return result;
    }

    @Override
    public Optional<ItemStack> chartItem(Player player, RegionId regionId) {
        Optional<WorldIndex.Entry> region = kit.index().get(regionId);
        if (region.isEmpty()) {
            return Optional.empty();
        }
        Optional<ItemStack> item = kit.createItem(HarborId.parse("item/tool/sea_chart"), 1);
        MapView view = view(region.get());
        if (item.isEmpty() || view == null) {
            return Optional.empty();
        }
        ItemStack stack = item.get();
        stack.editMeta(
                MapMeta.class,
                meta -> {
                    meta.setMapView(view);
                    meta.getPersistentDataContainer()
                            .set(
                                    kit.key("chart_region"),
                                    PersistentDataType.STRING,
                                    regionId.toString());
                });
        return Optional.of(stack);
    }

    // ------------------------------------------------------------------ map

    /**
     * Region MapView (the ID is kept in plugins/Harbor-World/charts.yml so the same map is used
     * after a restart).
     */
    private @Nullable MapView view(WorldIndex.Entry region) {
        MapView existing = views.get(region.id());
        if (existing != null) {
            return existing;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(mapFile);
        String path = region.id().toString().replace(':', '_').replace('/', '_');
        MapView view = null;
        if (yaml.isInt(path)) {
            view = Bukkit.getMap(yaml.getInt(path));
        }
        if (view == null) {
            World world = kit.bukkitWorld(region.def().world());
            if (world == null) {
                List<World> worlds = Bukkit.getWorlds();
                if (worlds.isEmpty()) {
                    return null;
                }
                world = worlds.getFirst();
            }
            view = Bukkit.createMap(world);
            yaml.set(path, view.getId());
            try {
                yaml.save(mapFile);
            } catch (IOException e) {
                kit.log().warn("charts.yml 저장 실패: {}", String.valueOf(e));
            }
        }
        for (MapRenderer r : List.copyOf(view.getRenderers())) {
            view.removeRenderer(r);
        }
        view.setTrackingPosition(false);
        view.setLocked(true);
        view.addRenderer(new Renderer(region.id()));
        views.put(region.id(), view);
        return view;
    }

    /** Per-player chart renderer (contextual). */
    private final class Renderer extends MapRenderer {

        private final RegionId region;
        private final Map<UUID, Integer> drawn = new HashMap<>();
        private final Map<UUID, Long> drawnAt = new HashMap<>();

        Renderer(RegionId region) {
            super(true);
            this.region = region;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            Optional<WorldIndex.Entry> found = kit.index().get(region);
            if (found.isEmpty()) {
                return;
            }
            WorldIndex.Entry e = found.get();
            UUID uuid = player.getUniqueId();
            int version = version(uuid);
            long now = System.currentTimeMillis();
            Integer last = drawn.get(uuid);
            if (last == null || (last != version && now - drawnAt.getOrDefault(uuid, 0L) >= 1000)) {
                draw(canvas, e, bits(uuid, e));
                drawn.put(uuid, version);
                drawnAt.put(uuid, now);
            }
            canvas.setCursors(cursors(e, player));
        }

        @Override
        public boolean isExplorerMap() {
            return false;
        }

        private void draw(MapCanvas canvas, WorldIndex.Entry e, ChartBits bits) {
            ChartMask mask = e.mask();
            if (mask.size() == 0) {
                return;
            }
            for (int py = 0; py < 128; py++) {
                int cz = mask.originZ() + py * mask.depth() / 128;
                for (int px = 0; px < 128; px++) {
                    int cx = mask.originX() + px * mask.width() / 128;
                    int cell = mask.cell(cx, cz);
                    Color color = !mask.navigable(cell) ? LAND : bits.has(cell) ? WATER : FOG;
                    canvas.setPixelColor(px, py, color);
                }
            }
        }

        private MapCursorCollection cursors(WorldIndex.Entry e, Player player) {
            MapCursorCollection cursors = new MapCursorCollection();
            Location at = WorldKit.loc(player);
            if (kit.worldId(at).equals(e.def().world())) {
                byte[] xy = toMap(e, at.getX(), at.getZ());
                if (xy != null) {
                    byte dir = (byte) Math.floorMod(Math.round(at.getYaw() / 22.5f), 16);
                    cursors.addCursor(
                            new MapCursor(xy[0], xy[1], dir, MapCursor.Type.PLAYER, true));
                }
            }
            for (FishingSpot spot : spots.get()) {
                if (spot.hidden() || !spot.region().equals(e.id())) {
                    continue;
                }
                byte[] xy = toMap(e, spot.x(), spot.z());
                if (xy != null
                        && bits(player.getUniqueId(), e)
                                .has(
                                        e.mask()
                                                .cell(
                                                        ((int) Math.floor(spot.x())) >> 4,
                                                        ((int) Math.floor(spot.z())) >> 4))) {
                    cursors.addCursor(
                            new MapCursor(
                                    xy[0], xy[1], (byte) 0, MapCursor.Type.TARGET_POINT, true));
                }
            }
            return cursors;
        }

        private byte @Nullable [] toMap(WorldIndex.Entry e, double x, double z) {
            ChartMask mask = e.mask();
            double minX = mask.originX() * 16.0;
            double minZ = mask.originZ() * 16.0;
            double w = mask.width() * 16.0;
            double d = mask.depth() * 16.0;
            if (w <= 0 || d <= 0) {
                return null;
            }
            double fx = (x - minX) / w;
            double fz = (z - minZ) / d;
            if (fx < 0 || fx > 1 || fz < 0 || fz > 1) {
                return null;
            }
            return new byte[] {
                (byte) Math.max(-128, Math.min(127, (int) (fx * 256 - 128))),
                (byte) Math.max(-128, Math.min(127, (int) (fz * 256 - 128)))
            };
        }
    }

    /** Bitmap for this session (empty if there is no HarborPlayer). */
    public Optional<HarborPlayer> online(UUID uuid) {
        return kit.api().players().get(uuid);
    }
}
