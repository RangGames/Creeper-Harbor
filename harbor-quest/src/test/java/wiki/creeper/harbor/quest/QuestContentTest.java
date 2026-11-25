package wiki.creeper.harbor.quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.quest.content.BoardRules;
import wiki.creeper.harbor.quest.content.ChronicleDef;
import wiki.creeper.harbor.quest.content.ObjectiveMode;
import wiki.creeper.harbor.quest.content.QuestContent;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.content.Target;

class QuestContentTest {

    @TempDir Path root;
    ContentLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        loader = new ContentLoader();
        CoreContent.register(loader);
        QuestContent.register(loader);
        write("regions.yml", "[]\n");
        write("balance.yml", "jobs: {quality_cap: {1: 3, 2: 4, 3: 5}}\n");
        write("messages_ko.yml", "quest: {}\n");
    }

    void write(String file, String text) throws IOException {
        Path p = root.resolve(file);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    Result<ContentSnapshot, ValidationErrors> load() {
        return loader.load(root);
    }

    static String errors(Result<ContentSnapshot, ValidationErrors> r) {
        return r instanceof Result.Err<ContentSnapshot, ValidationErrors> err
                ? err.error().format()
                : "";
    }

    @Test
    void parsesMainQuestWithDefaults() throws IOException {
        write(
                "quests/main.yml",
                """
                - id: quest/main/ch1_a
                  name: A
                  type: main
                  chapter: 1
                  end_npc: npc/bawi
                  objectives:
                    - {key: fish, type: catch, target: "grade:uncommon+", count: 3, quality_min: 2}
                    - {type: talk, target: any, npc: npc/bawi}
                    - {key: give, type: deliver, target: any, count: 2}
                  rewards: {gold: 100, mulbineul: 3, chronicle: k}
                """);
        write(
                "chronicle.yml",
                "chapters: [{chapter: 1, name: c1, final: quest/main/ch1_a}]\npieces: [{id: k, chapter: 1, name: key}]\n");
        Result<ContentSnapshot, ValidationErrors> r = load();
        assertTrue(r.isOk(), errors(r));
        QuestDef q =
                r.orThrow().get(QuestDef.TYPE, HarborId.parse("quest/main/ch1_a")).orElseThrow();
        assertEquals(QuestType.MAIN, q.type());
        assertEquals(ObjectiveMode.SEQUENCE, q.mode());
        assertFalse(q.abandonable());
        assertEquals("o2", q.objectives().get(1).key());
        assertEquals(Target.Kind.GRADE, q.objectives().getFirst().target().kind());
        assertTrue(q.objectives().getFirst().target().orAbove());
        ChronicleDef chronicle = r.orThrow().single(ChronicleDef.TYPE);
        assertEquals(3, chronicle.recapMulbineul());
        // One defined piece does not make the full chronicle (chapters 1~7)
        assertEquals(ChronicleDef.DEFAULT_REQUIRED_PIECES, chronicle.totalPieces());
    }

    @Test
    void rejectsQualityAboveTierCap() throws IOException {
        write(
                "quests/a.yml",
                """
                - id: quest/region/a
                  name: A
                  type: region
                  objectives: [{type: catch, target: any, quality_min: 4}]
                """);
        assertFalse(load().isOk());
    }

    @Test
    void rejectsPrerequisiteCycle() throws IOException {
        write(
                "quests/a.yml",
                """
                - {id: quest/region/a, name: A, type: region, requires: {quests: [quest/region/b]}, objectives: [{type: catch}]}
                - {id: quest/region/b, name: B, type: region, requires: {quests: [quest/region/a]}, objectives: [{type: catch}]}
                """);
        assertTrue(errors(load()).contains("순환"));
    }

    @Test
    void rejectsDeliverBeforeTrackedInSequence() throws IOException {
        write(
                "quests/a.yml",
                """
                - id: quest/main/ch1_a
                  name: A
                  type: main
                  chapter: 1
                  end_npc: npc/bawi
                  objectives: [{type: deliver, target: any}, {type: catch}]
                """);
        assertTrue(errors(load()).contains("deliver"));
    }

    @Test
    void boardQuestNeedsPoolAndUsesBoardDefaults() throws IOException {
        write(
                "quests/a.yml",
                """
                - {id: quest/daily/a, name: A, type: daily, objectives: [{type: catch}]}
                - {id: quest/daily/b, name: B, type: daily, pool: {board: daily}, objectives: [{type: catch}]}
                """);
        assertTrue(errors(load()).contains("pool"));
        write(
                "quests/a.yml",
                """
                - {id: quest/daily/b, name: B, type: daily, pool: {board: daily}, objectives: [{type: catch}]}
                """);
        Result<ContentSnapshot, ValidationErrors> r = load();
        assertTrue(r.isOk(), errors(r));
        QuestDef q = r.orThrow().get(QuestDef.TYPE, HarborId.parse("quest/daily/b")).orElseThrow();
        assertEquals(-1, q.rewards().gold());
        assertEquals(-1, q.rewards().reputation());
    }

    @Test
    void parsesShorthandObjectives() throws IOException {
        write(
                "quests/a.yml",
                """
                - id: quest/region/a
                  name: A
                  type: region
                  end_npc: npc/bawi
                  objectives:
                    - {catch: "grade:rare+", count: 2}
                    - {talk: npc/bawi}
                    - {job_level: 5}
                    - {key: custom, visit: "point:harbor_main,1,2,3,4"}
                """);
        Result<ContentSnapshot, ValidationErrors> r = load();
        assertTrue(r.isOk(), errors(r));
        QuestDef q = r.orThrow().get(QuestDef.TYPE, HarborId.parse("quest/region/a")).orElseThrow();
        var o = q.objectives();
        assertEquals("catch", o.get(0).type());
        assertEquals("catch_grade_rare", o.get(0).key());
        assertEquals(2, o.get(0).count());
        assertEquals(Target.Kind.GRADE, o.get(0).target().kind());
        assertEquals("talk_bawi", o.get(1).key());
        assertEquals("job_level", o.get(2).key());
        assertEquals(5, o.get(2).count());
        assertEquals("custom", o.get(3).key());
        assertEquals(Target.Kind.POINT, o.get(3).target().kind());
    }

    @Test
    void rejectsShorthandWithTargetOrTwoTypes() throws IOException {
        write(
                "quests/a.yml",
                "- {id: quest/region/a, name: A, type: region, objectives: [{catch: any, target: any}]}\n");
        assertTrue(errors(load()).contains("축약형"));
        write(
                "quests/a.yml",
                "- {id: quest/region/a, name: A, type: region, objectives: [{catch: any, boss: any}]}\n");
        assertTrue(errors(load()).contains("두 개"));
    }

    @Test
    void rejectsUnknownObjectiveType() throws IOException {
        write(
                "quests/a.yml",
                "- {id: quest/region/a, name: A, type: region, objectives: [{type: dance}]}\n");
        assertTrue(errors(load()).contains("dance"));
    }

    /**
     * The repository's actual content (quests/*.yml, board, chronicle, progress) passes the same
     * loader as the server.
     */
    @Test
    void repositoryContentLoads() {
        Path content = Path.of("..", "content");
        if (!Files.isDirectory(content)) {
            return;
        }
        ContentLoader real = new ContentLoader();
        CoreContent.register(real);
        QuestContent.register(real);
        Result<ContentSnapshot, ValidationErrors> r = real.load(content);
        assertTrue(r.isOk(), errors(r));
        ContentSnapshot s = r.orThrow();
        assertTrue(s.all(QuestDef.TYPE).size() >= 10);
        assertEquals(7, s.single(BoardRules.TYPE).ladder().size());
    }
}
