package wiki.creeper.harbor.wildlife;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationError;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.fishing.content.FishingContent;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.content.BehaviourBook;
import wiki.creeper.harbor.wildlife.content.WildGroup;
import wiki.creeper.harbor.wildlife.content.WildSpecies;
import wiki.creeper.harbor.wildlife.content.WildlifeContent;
import wiki.creeper.harbor.wildlife.sim.ProfileIndex;
import wiki.creeper.harbor.wildlife.sim.SpeciesProfile;

/**
 * Reads the repository's content/ with the same loader as the server and checks the Wildlife files
 * have no errors (errors in other modules' files are out of scope).
 */
class WildlifeContentTest {

    private static final Set<String> OWNED = Set.of("wildlife/", "rigs/wildlife.yml");

    @Test
    void wildlifeContentParses() {
        Path dir = Path.of("..", "content");
        assumeTrue(Files.isDirectory(dir), "content 폴더가 없습니다");
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        FishingContent.TYPES.forEach(loader::register);
        WildlifeContent.TYPES.forEach(loader::register);
        loader.addCheck(WildSpecies.TYPE, WildlifeContent.SPECIAL_CHECK);
        loader.addCheck(WildSpecies.TYPE, WildlifeContent.FISH_UNIQUE);
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
                assertTrue(mine.isEmpty(), () -> "Wildlife 콘텐츠 오류: " + mine);
                assumeTrue(false, "다른 콘텐츠 오류로 건너뜀: " + errors.size() + "건");
            }
            case Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot snapshot) ->
                    check(snapshot);
        }
    }

    private static void check(ContentSnapshot snapshot) {
        BehaviourBook book = snapshot.single(BehaviourBook.TYPE);
        assertEquals(0.09, book.group(WildGroup.ACTIVE).specialChance(), 1e-9, "시제품 active 9%");
        assertEquals(0.08, book.group(WildGroup.LAZY).idleChance(), 1e-9, "시제품 lazy 쉬기 8%");
        assertEquals(1.6, book.action("dart").orElseThrow().speedDelta(), 1e-9);
        assertEquals(0.65, book.action("leap").orElseThrow().impulse().y(), 1e-9);

        ProfileIndex index =
                ProfileIndex.build(
                        snapshot,
                        new wiki.creeper.harbor.wildlife.content.WildlifeBalance(
                                snapshot.single(
                                        wiki.creeper.harbor.common.content.type.Balance.TYPE)));
        assertTrue(index.problems().isEmpty(), () -> "건너뛴 어종: " + index.problems());
        List<SpeciesProfile> harbor = index.region(RegionId.parse("region/harbor"));
        assertTrue(harbor.size() >= 16, "항구 어종 16종 + 신화");
        assertTrue(harbor.stream().filter(SpeciesProfile::naturalSpawn).count() >= 16);
        assertEquals(Set.of(Medium.WATER), index.mediums(RegionId.parse("region/harbor")));

        SpeciesProfile mackerel = index.byFish().get(FishId.parse("fish/harbor_mackerel"));
        assertNotNull(mackerel);
        assertEquals(
                "harbor:rig/fish/harbor_mackerel", mackerel.rig().toString(), "어종 리그가 템플릿보다 우선");
        assertEquals(WildGroup.ACTIVE, mackerel.group(), "dart → active");
        assertEquals(4, mackerel.schoolMin());

        SpeciesProfile squid = index.byFish().get(FishId.parse("fish/little_squid"));
        assertNotNull(squid);
        // finds the species rig via FishDef.model even when the species ID (little_squid) and model
        // name (baby_squid) differ
        assertEquals("harbor:rig/fish/baby_squid", squid.rig().toString());
        assertNotNull(squid.threatAction());
        assertEquals("ink", squid.threatAction().name());

        SpeciesProfile hairtail = index.byFish().get(FishId.parse("fish/lighthouse_hairtail"));
        assertNotNull(hairtail);
        assertTrue(hairtail.length() >= 1.0);
        assertTrue(hairtail.schoolMax() <= 2, "몸길이 1블록 이상은 1~2마리");

        SpeciesProfile leader = index.byFish().get(FishId.parse("fish/migration_leader"));
        assertNotNull(leader);
        assertFalse(leader.naturalSpawn(), "신화는 자연 스폰 없음");
    }
}
