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
        assertEquals(3, r.orThrow().single(ChronicleDef.TYPE).recapMulbineul());
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
    void rejectsUnknownObjectiveType() throws IOException {
        write(
                "quests/a.yml",
                "- {id: quest/region/a, name: A, type: region, objectives: [{type: dance}]}\n");
        assertTrue(errors(load()).contains("dance"));
    }

    /** 저장소의 실제 콘텐츠 (quests/*.yml, board·chronicle·progress)가 서버와 같은 로더로 통과한다. */
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
