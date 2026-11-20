package wiki.creeper.harbor.quest.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.quest.content.QuestType;

class QuestDraftTest {

    static final String FILE =
            """
            - id: quest/region/a
              name: A
              objectives:
                - {catch: "grade:rare+", count: 2}
                - {type: talk, target: npc/bawi}
                - {job_level: 5}
              rewards: {gold: 100}
              custom_field: keep
            """;

    @Test
    void normalizesObjectivesKeepingEffectiveKeys() {
        QuestDraft d =
                QuestDraft.of("region.yml", QuestYaml.read(FILE, "quest/region/a").orElseThrow());
        List<Map<String, Object>> o = d.objectives();
        assertEquals("catch_grade_rare", o.get(0).get("key"));
        assertEquals("catch", o.get(0).get("type"));
        assertEquals("grade:rare+", o.get(0).get("target"));
        assertEquals("o2", o.get(1).get("key"), "기본형 key 생략은 순서 번호");
        assertEquals("job_level", o.get(2).get("key"));
        assertEquals("5", o.get(2).get("count"));
        assertFalse(d.dirty());
        assertEquals("keep", d.entry().get("custom_field"), "편집기가 모르는 키도 남는다");
    }

    @Test
    void editsAndMoves() {
        QuestDraft d =
                QuestDraft.of("region.yml", QuestYaml.read(FILE, "quest/region/a").orElseThrow());
        assertEquals(200, d.add("rewards", "gold", 100));
        assertEquals(0, d.add("rewards", "exp", -10));
        assertFalse(d.section("rewards").containsKey("exp"));
        d.moveObjective(0, 1);
        assertEquals("o2", d.objectives().get(0).get("key"));
        Map<String, Object> added = d.addObjective("catch");
        assertEquals("catch", added.get("key"));
        assertEquals("catch_2", d.addObjective("catch").get("key"));
        d.description("첫 줄\n\n 둘째 줄 ");
        assertEquals(List.of("첫 줄", "둘째 줄"), d.description());
        assertTrue(d.dirty());
    }

    @Test
    void newQuestTemplate() {
        QuestDraft d = QuestDraft.create("quest/daily/x", "X", QuestType.DAILY);
        assertEquals("commissions.yml", d.file());
        assertEquals("daily", d.section("pool").get("board"));
        String text = QuestYaml.emit(d.entry());
        assertTrue(text.startsWith("- id: quest/daily/x\n  name: X\n  type: daily\n"), text);
    }
}
