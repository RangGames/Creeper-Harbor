package wiki.creeper.harbor.boss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.content.BossContent;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.content.BossType;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationError;
import wiki.creeper.harbor.common.util.ValidationErrors;

/** bosses/*.yml 파서와 저장소 콘텐츠 (Boss가 맡은 파일만 오류를 본다). */
class BossContentTest {

    private static final List<String> OWNED =
            List.of(
                    "bosses/",
                    "rigs/boss_sea.yml",
                    "rigs/boss.yml",
                    "items/boss.yml",
                    "fish/boss.yml");

    private static ContentLoader loader() {
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        BossContent.TYPES.forEach(loader::register);
        loader.addCheck(BossDef.TYPE, BossContent.RIG_CHECK);
        loader.addCheck(BossDef.TYPE, BossContent.FISH_CHECK);
        return loader;
    }

    @Test
    void repositoryContentParses() {
        Path dir = Path.of("..", "content");
        assumeTrue(Files.isDirectory(dir), "content 폴더가 없습니다");
        switch (loader().load(dir)) {
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
                assertTrue(mine.isEmpty(), () -> "Boss 콘텐츠 오류: " + mine);
                assumeTrue(false, "다른 콘텐츠 오류로 건너뜀: " + errors.size() + "건");
            }
            case Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot snapshot) -> {
                BossDef gnome =
                        snapshot.get(BossDef.TYPE, HarborId.parse("boss/swamp_catfish"))
                                .orElseThrow();
                assertEquals(BossType.INTRO, gnome.type());
                assertEquals(2000, gnome.hp().base(), 1e-9);
                assertEquals(0.2, gnome.finalTugBelow(), 1e-9);
                assertTrue(gnome.partByBone("head").orElseThrow().weakpoint());
                BossDef world =
                        snapshot.get(BossDef.TYPE, HarborId.parse("boss/green_thunder_fish"))
                                .orElseThrow();
                assertTrue(world.isWorld());
                assertEquals(BossDef.Goal.CALM, world.goal());
                BossDef raid =
                        snapshot.get(BossDef.TYPE, HarborId.parse("boss/trench_lord_raid"))
                                .orElseThrow();
                assertEquals(8, raid.loot().pityAfter());
                assertEquals(BossDef.ArenaMode.INSTANCE, raid.arena().mode());
                BossDef story =
                        snapshot.get(BossDef.TYPE, HarborId.parse("boss/trench_lord_story"))
                                .orElseThrow();
                assertTrue(story.story());
                BossBalance bb = new BossBalance(snapshot.single(Balance.TYPE));
                assertEquals(5, bb.weeklyLimit(BossType.INTRO));
                assertEquals(1200, bb.timeLimitSeconds(BossType.RAID));
                assertEquals(10, bb.worldMinParticipants());
            }
        }
    }

    @Test
    void parserRejectsBadPhasesAndUnusedPatterns() {
        String yaml =
                """
                - id: boss/bad
                  name: 나쁜 보스
                  type: intro
                  region: region/harbor
                  arena: {mode: field, world: harbor_main, slots: [{center: [0, 62, 0], radius: 16}]}
                  hp: {base: 100}
                  rig: rig/boss/gnome
                  parts: [{id: head, bone: head}]
                  phases: [{id: counter, patterns: [nope]}, {id: hook}]
                  patterns: [{id: unused, telegraph: {shape: circle, size: 2}}]
                """;
        ValidationErrors errors = new ValidationErrors();
        var node =
                wiki.creeper.harbor.common.yaml.YamlNode.parse(
                                "bosses/bad.yml", new StringReader(yaml), errors)
                        .orElseThrow();
        var ctx = new wiki.creeper.harbor.common.content.ParseContext();
        for (var entry : node.asList()) {
            var parser = BossDef.TYPE.parser();
            parser.parse(entry, ctx);
        }
        String all = errors.list().toString();
        assertFalse(errors.list().isEmpty());
        assertTrue(all.contains("hook, counter, final_tug"), all);
        assertTrue(all.contains("nope"), all);
        assertTrue(all.contains("unused"), all);
    }

    @Test
    void balanceDefaultsMatchDocument() {
        BossBalance bb = new BossBalance(Balance.of(Map.of()));
        assertEquals(0.6, bb.perPlayerRate(BossType.INTRO), 1e-9);
        assertEquals(0.4, bb.perPlayerRate(BossType.RAID), 1e-9);
        assertEquals(360, bb.timeLimitSeconds(BossType.INTRO));
        assertEquals(3, bb.weeklyLimit(BossType.SEA));
        assertEquals(3000, bb.clearGold(BossType.RAID));
        assertEquals(30, bb.telegraphTicks(BossType.INTRO));
        assertEquals(2, bb.anchorsMin(BossType.RAID));
    }
}
