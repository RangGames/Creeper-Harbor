package wiki.creeper.harbor.quest.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.quest.content.QuestContent;
import wiki.creeper.harbor.quest.content.QuestDef;

class QuestYamlTest {

    @TempDir Path temp;

    static final String FILE =
            """
            # 머리 주석
            - id: quest/region/a
              name: A
              # 안쪽 주석
              objectives:
                - {type: catch, target: any}

            # ---- B 머리 주석
            - id: quest/region/b
              name: B
              objectives: [{type: catch}]
            """;

    @Test
    void replaceKeepsOtherBlocksAndComments() {
        Map<String, Object> a = QuestYaml.read(FILE, "quest/region/a").orElseThrow();
        a.put("name", "새 이름");
        String out = QuestYaml.replace(FILE, "harbor:quest/region/a", QuestYaml.emit(a));
        assertTrue(out.startsWith("# 머리 주석\n- id: quest/region/a\n  name: \"새 이름\"\n"), out);
        assertTrue(out.contains("\n# ---- B 머리 주석\n- id: quest/region/b\n"), out);
        assertFalse(out.contains("안쪽 주석"));
        assertEquals("새 이름", QuestYaml.read(out, "quest/region/a").orElseThrow().get("name"));
    }

    @Test
    void appendsWhenMissingAndRemoves() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", "quest/region/c");
        c.put("name", "C");
        c.put("objectives", List.of(Map.of("talk", "npc/bawi")));
        String out = QuestYaml.replace(FILE, "quest/region/c", QuestYaml.emit(c));
        assertTrue(QuestYaml.contains(out, "quest/region/c"));
        String removed = QuestYaml.remove(out, "quest/region/b");
        assertFalse(QuestYaml.contains(removed, "quest/region/b"));
        assertTrue(removed.contains("quest/region/c"));
        assertTrue(removed.contains("# ---- B 머리 주석"), "다음 항목 머리 주석은 지우지 않는다");
    }

    @Test
    void keepsCrlf() {
        String crlf = FILE.replace("\n", "\r\n");
        Map<String, Object> a = QuestYaml.read(crlf, "quest/region/a").orElseThrow();
        String out = QuestYaml.replace(crlf, "quest/region/a", QuestYaml.emit(a));
        assertFalse(out.replace("\r\n", "").contains("\n"));
    }

    @Test
    void scalarQuoting() {
        assertEquals("fish/harbor_mackerel", QuestYaml.scalar("fish/harbor_mackerel", true));
        assertEquals("\"grade:uncommon+\"", QuestYaml.scalar("grade:uncommon+", true));
        assertEquals("grade:uncommon+", QuestYaml.scalar("grade:uncommon+", false));
        assertEquals("\"a: b\"", QuestYaml.scalar("a: b", false));
        assertEquals("\"true\"", QuestYaml.scalar("true", false));
        assertEquals("\"말 \\\"따옴표\\\"\"", QuestYaml.scalar("말 \"따옴표\"", false));
        assertEquals("300", QuestYaml.scalar("300", false));
    }

    /**
     * Reading every quest in the repository, writing it back and reading it with the same loader
     * yields the same name, objectives and rewards (editor saves do not change content).
     */
    @Test
    void repositoryQuestsRoundTrip() throws IOException {
        Path content = Path.of("..", "content").toAbsolutePath().normalize();
        if (!Files.isDirectory(content.resolve("quests"))) {
            return;
        }
        Path copy = temp.resolve("content");
        try (Stream<Path> files = Files.walk(content)) {
            for (Path p : files.toList()) {
                Path to = copy.resolve(content.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to);
                }
            }
        }
        ContentSnapshot before = load(copy);
        List<Path> questFiles = new ArrayList<>();
        try (Stream<Path> files = Files.list(copy.resolve("quests"))) {
            files.filter(p -> p.toString().endsWith(".yml")).forEach(questFiles::add);
        }
        int rewritten = 0;
        for (Path file : questFiles) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            for (QuestDef def : before.all(QuestDef.TYPE).values()) {
                String id = def.id().id().toString();
                if (!QuestYaml.contains(text, id)) {
                    continue;
                }
                Map<String, Object> entry = QuestYaml.read(text, id).orElseThrow();
                text = QuestYaml.replace(text, id, QuestYaml.emit(entry));
                rewritten++;
            }
            Files.writeString(file, text, StandardCharsets.UTF_8);
        }
        assertEquals(before.all(QuestDef.TYPE).size(), rewritten);
        ContentSnapshot after = load(copy);
        for (QuestDef a : before.all(QuestDef.TYPE).values()) {
            QuestDef b = after.get(QuestDef.TYPE, a.id().id()).orElseThrow();
            assertEquals(a.name(), b.name());
            assertEquals(a.description(), b.description());
            assertEquals(a.hash(), b.hash(), a.id().toString());
            assertEquals(a.objectives(), b.objectives(), a.id().toString());
            assertEquals(a.rewards(), b.rewards(), a.id().toString());
            assertEquals(a.requires(), b.requires(), a.id().toString());
        }
    }

    private static ContentSnapshot load(Path root) {
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        QuestContent.register(loader);
        Result<ContentSnapshot, ValidationErrors> r = loader.load(root);
        assertTrue(
                r.isOk(),
                r instanceof Result.Err<ContentSnapshot, ValidationErrors> err
                        ? err.error().format()
                        : "");
        return r.orThrow();
    }
}
