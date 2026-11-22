package wiki.creeper.harbor.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationError;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.fishing.content.FishingContent;
import wiki.creeper.harbor.world.api.SeaEventType;
import wiki.creeper.harbor.world.api.ShipFlag;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.content.SeaEventDef;
import wiki.creeper.harbor.world.content.ShipDef;
import wiki.creeper.harbor.world.content.TreasureTable;
import wiki.creeper.harbor.world.content.WorldContent;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * Reads the repository's content/ with the same loader as the server and checks the World files for
 * errors (errors in other modules' files are out of scope).
 */
class WorldContentTest {

    private static final Set<String> OWNED =
            Set.of("world/", "items/world.yml", "rigs/ships.yml", "regions.yml");

    @Test
    void worldContentParses() {
        Path dir = Path.of("..", "content");
        assumeTrue(Files.isDirectory(dir), "content 폴더가 없습니다");
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        FishingContent.TYPES.forEach(loader::register);
        WorldContent.TYPES.forEach(loader::register);
        Result<ContentSnapshot, ValidationErrors> result = loader.load(dir);
        switch (result) {
            case Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors) -> {
                List<ValidationError> mine =
                        errors.list().stream()
                                .filter(
                                        e -> {
                                            var pos = e.pos();
                                            String file =
                                                    pos == null
                                                            ? ""
                                                            : pos.file().replace('\\', '/');
                                            return OWNED.stream().anyMatch(file::contains);
                                        })
                                .toList();
                assertTrue(mine.isEmpty(), () -> "World 콘텐츠 오류: " + mine);
                assumeTrue(false, "다른 콘텐츠 오류로 건너뜀: " + errors.size() + "건");
            }
            case Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot snapshot) -> {
                assertEquals(7, snapshot.all(RegionDef.TYPE).size());
                for (RegionDef r : snapshot.all(RegionDef.TYPE).values()) {
                    assertTrue(
                            r.trapZone().size() >= 40 && r.trapZone().size() <= 60,
                            () -> "부표 자리 40~60개: " + r.id());
                    for (RegionDef.Point p : r.trapZone()) {
                        assertTrue(
                                r.contains(p.x(), p.z()), () -> "부표 자리가 지역 밖: " + r.id() + " " + p);
                    }
                }
                RegionExt fjord =
                        snapshot.get(RegionExt.TYPE, HarborId.parse("region/frost_fjord"))
                                .orElseThrow();
                assertTrue(fjord.unlock().firstEntryOnly());
                assertTrue(fjord.hasEnv(RegionExt.EnvType.COLD));
                ShipDef explorer =
                        snapshot.get(ShipDef.TYPE, HarborId.parse("ship/ocean_explorer"))
                                .orElseThrow();
                assertEquals(60000, explorer.price());
                assertEquals(50, explorer.requires().level());
                assertEquals(2, explorer.requires().jobTier());
                assertTrue(explorer.has(ShipFlag.STORM));
                SeaEventDef tour =
                        snapshot.get(SeaEventDef.TYPE, HarborId.parse("event/weekly_tournament"))
                                .orElseThrow();
                assertEquals(SeaEventType.TOURNAMENT, tour.type());
                assertTrue(
                        snapshot.single(TreasureTable.TYPE)
                                .tier(
                                        "ghost",
                                        wiki.creeper.harbor.common.id.RegionId.parse(
                                                "region/harbor"))
                                .isPresent());
                WorldIndex index = WorldIndex.build(snapshot);
                assertEquals(
                        "harbor:region/harbor",
                        index.at("harbor_main", 0, 0).orElseThrow().id().toString());
                assertEquals(
                        "harbor:region/coral_isles",
                        index.at("harbor_main", 0, 2000).orElseThrow().id().toString());
                assertTrue(index.at("harbor_main", 3000, 3000).isEmpty());
                assertEquals(
                        List.of("lighthouse"),
                        index.at("harbor_main", -10, -60).orElseThrow().areasAt(-10, 80, -60));
                // There must be more school candidates than fishing_spots so spots have somewhere
                // to move
                for (WorldIndex.Entry e : index.regions()) {
                    assertTrue(
                            e.ext().spotCandidates().size() > e.def().fishingSpots(),
                            () -> "물고기 떼 후보 부족: " + e.id());
                }
            }
        }
    }
}
