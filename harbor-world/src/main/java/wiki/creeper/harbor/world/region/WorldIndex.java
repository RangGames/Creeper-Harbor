package wiki.creeper.harbor.world.region;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.RegionInfo;
import wiki.creeper.harbor.world.chart.ChartMask;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.geo.Polygon;
import wiki.creeper.harbor.world.geo.RegionIndex;

/**
 * 콘텐츠 스냅샷에서 만든 지역 색인 묶음 (지역 경계·세부 구역·청크 색인·해도 마스크). 가상 스레드에서 만들고 참조 교체만 메인 스레드에서 한다 (docs 20 8.4
 * '스레드'). 변경 불가라 어느 스레드에서 읽어도 된다. 서버 API 없음.
 */
public final class WorldIndex {

    /** 세부 구역 도형. */
    public record AreaShape(RegionExt.Area area, Polygon polygon) {

        public boolean contains(double x, double y, double z) {
            return y >= area.minY() && y <= area.maxY() && polygon.contains(x, z);
        }
    }

    /** 지역 하나. */
    public record Entry(
            int index,
            RegionDef def,
            RegionExt ext,
            RegionInfo info,
            Polygon polygon,
            List<AreaShape> areas,
            ChartMask mask) {

        public RegionId id() {
            return def.id();
        }

        public List<String> areasAt(double x, double y, double z) {
            List<String> result = new ArrayList<>(2);
            for (AreaShape a : areas) {
                if (a.contains(x, y, z)) {
                    result.add(a.area().id());
                }
            }
            return result;
        }

        public boolean inArea(@Nullable String areaId, double x, double y, double z) {
            if (areaId == null) {
                return true;
            }
            for (AreaShape a : areas) {
                if (a.area().id().equals(areaId)) {
                    return a.contains(x, y, z);
                }
            }
            return false;
        }
    }

    private record WorldRegions(RegionIndex index, List<Entry> entries) {}

    public static final WorldIndex EMPTY = new WorldIndex(List.of(), Map.of());

    private final List<Entry> ordered;
    private final Map<HarborId, Entry> byId;
    private final Map<String, WorldRegions> byWorld;

    private WorldIndex(List<Entry> ordered, Map<String, WorldRegions> byWorld) {
        this.ordered = ordered;
        Map<HarborId, Entry> ids = new HashMap<>();
        ordered.forEach(e -> ids.put(e.id().id(), e));
        this.byId = Map.copyOf(ids);
        this.byWorld = byWorld;
    }

    /** 스냅샷에서 만든다 (수 MB, 약 0.1초 — 가상 스레드에서). */
    public static WorldIndex build(ContentSnapshot snapshot) {
        List<RegionDef> defs = new ArrayList<>(snapshot.all(RegionDef.TYPE).values());
        Map<HarborId, RegionExt> exts =
                snapshot.typeNames().contains(RegionExt.TYPE.name())
                        ? snapshot.all(RegionExt.TYPE)
                        : Map.of();
        List<Entry> entries = new ArrayList<>();
        for (RegionDef def : defs) {
            RegionExt ext = exts.get(def.id().id());
            if (ext == null) {
                ext = defaultExt(def.id());
            }
            Polygon polygon = new Polygon(def.bounds());
            List<AreaShape> areas = new ArrayList<>();
            for (RegionExt.Area a : ext.areas()) {
                areas.add(new AreaShape(a, new Polygon(a.bounds())));
            }
            RegionInfo info =
                    new RegionInfo(
                            def.id(),
                            def.name(),
                            def.world(),
                            def.biome(),
                            ext.order(),
                            def.recLevel(),
                            ext.hourlyGold(),
                            ext.areas().stream().map(RegionExt.Area::id).toList(),
                            ext.server());
            entries.add(
                    new Entry(
                            0, def, ext, info, polygon, List.copyOf(areas), ChartMask.of(polygon)));
        }
        entries.sort(Comparator.comparingInt((Entry e) -> e.ext().order()));
        List<Entry> indexed = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            indexed.add(new Entry(i, e.def(), e.ext(), e.info(), e.polygon(), e.areas(), e.mask()));
        }
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        for (Entry e : indexed) {
            grouped.computeIfAbsent(e.def().world(), w -> new ArrayList<>()).add(e);
        }
        Map<String, WorldRegions> byWorld = new HashMap<>();
        grouped.forEach(
                (world, list) ->
                        byWorld.put(
                                world,
                                new WorldRegions(
                                        RegionIndex.build(
                                                list.stream().map(Entry::polygon).toList()),
                                        List.copyOf(list))));
        return new WorldIndex(List.copyOf(indexed), Map.copyOf(byWorld));
    }

    private static RegionExt defaultExt(RegionId id) {
        return new RegionExt(
                id,
                99,
                0,
                null,
                null,
                RegionExt.Unlock.NONE,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    public List<Entry> regions() {
        return ordered;
    }

    public Optional<Entry> get(RegionId id) {
        return Optional.ofNullable(byId.get(id.id()));
    }

    public Optional<Entry> get(HarborId id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * @param worldId 콘텐츠 월드 이름 (alias 적용 후)
     */
    public Optional<Entry> at(String worldId, double x, double z) {
        WorldRegions w = byWorld.get(worldId);
        if (w == null) {
            return Optional.empty();
        }
        int r = w.index().lookup(x, z);
        return r == RegionIndex.NONE ? Optional.empty() : Optional.of(w.entries().get(r));
    }

    /** 청크가 한 지역에 통째로 들어가면 그 지역 (이동 판정 캐시용). */
    public Optional<Entry> wholeChunk(String worldId, int cx, int cz) {
        WorldRegions w = byWorld.get(worldId);
        if (w == null) {
            return Optional.empty();
        }
        int r = w.index().wholeChunk(cx, cz);
        return r == RegionIndex.NONE ? Optional.empty() : Optional.of(w.entries().get(r));
    }

    /** 통계 (/harbor world region list): 월드별 [청크 수, 경계 청크 수]. */
    public Map<String, int[]> stats() {
        Map<String, int[]> result = new LinkedHashMap<>();
        byWorld.forEach(
                (k, v) ->
                        result.put(k, new int[] {v.index().chunkCount(), v.index().mixedChunks()}));
        return result;
    }
}
