package wiki.creeper.harbor.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import wiki.creeper.harbor.npc.api.NpcRole;
import wiki.creeper.harbor.npc.content.CutsceneDef;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.DialogRef;
import wiki.creeper.harbor.npc.content.NpcContent;
import wiki.creeper.harbor.npc.content.NpcDef;

class NpcContentTest {

    @TempDir Path root;
    ContentLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        loader = new ContentLoader();
        CoreContent.register(loader);
        NpcContent.TYPES.forEach(loader::register);
        loader.addCheck(DialogDef.TYPE, NpcContent.DIALOG_MESSAGES);
        loader.addCheck(CutsceneDef.TYPE, NpcContent.CUTSCENE_MESSAGES);
        write("regions.yml", "[]\n");
        write("balance.yml", "npc: {}\n");
        write(
                "messages_ko.yml",
                """
                npc:
                  t:
                    hello: "안녕"
                    more: "더"
                    bye: "잘 가"
                    line: "대사"
                """);
        write(
                "npcs.yml",
                """
                - id: npc/hansol
                  name: "<gold>항구장 한솔"
                  role: story
                  location: {world: harbor_main, x: 1, y: 64, z: 2, yaw: 90}
                  skin: harbor:entity/npc/hansol
                  dialog: dialog/hansol
                  name_variants: [{player_flag: story/x, name: 변형}]
                - id: npc/peddler
                  name: 행상
                  role: wanderer
                  schedule:
                    slot_hours: 3
                    candidates:
                      - {id: a, location: {world: harbor_main, x: 0, y: 64, z: 0}, weight: 1}
                      - {id: b, location: {world: harbor_main, x: 5, y: 64, z: 0}, time: [night], weather: [storm]}
                """);
        write(
                "cutscenes/c.yml",
                cutscene("[{t: 0, pos: [0, 70, 0]}, {t: 40, pos: [5, 70, 0], yaw: 90}]"));
    }

    @Test
    void parsesNpcDialogAndCutscene() throws IOException {
        write("dialogs/hansol.yml", dialog("goto: more"));
        ContentSnapshot s = loader.load(root).orThrow();

        NpcDef hansol = s.get(NpcDef.TYPE, HarborId.parse("npc/hansol")).orElseThrow();
        assertEquals(NpcRole.STORY, hansol.role());
        assertNotNull(hansol.location());
        assertEquals(6, hansol.lookRadius());
        assertEquals("story/x", hansol.nameVariants().getFirst().playerFlag());
        assertNotNull(hansol.skin());

        NpcDef peddler = s.get(NpcDef.TYPE, HarborId.parse("npc/peddler")).orElseThrow();
        NpcDef.Schedule schedule = peddler.schedule();
        assertNotNull(schedule);
        assertTrue(schedule.candidate("b").orElseThrow().conditional());

        DialogDef dialog = s.get(DialogDef.TYPE, HarborId.parse("dialog/hansol")).orElseThrow();
        assertEquals("start", dialog.entries().getLast().node());
        DialogRef cond = dialog.entries().getFirst().conditions().getFirst();
        assertEquals("affinity_min", cond.key());
        assertEquals(2, cond.args().integer("level", 0));
        DialogRef act = dialog.nodes().get("start").options().getFirst().actions().getFirst();
        assertEquals("flag:set", act.key());
        assertEquals("met", act.args().string("flag").orElseThrow());
        assertTrue(dialog.unreachable().isEmpty());

        CutsceneDef c = s.get(CutsceneDef.TYPE, HarborId.parse("cutscene/c")).orElseThrow();
        assertEquals(60, c.duration()); // Last line t 0 + 60 ticks
        assertTrue(c.returnsToOrigin());
    }

    @Test
    void rejectsMissingGotoAndUnreachableNode() throws IOException {
        write("dialogs/hansol.yml", dialog("goto: nowhere"));
        ValidationErrors errors = errors();
        String text = errors.format();
        assertTrue(text.contains("nowhere"), text);
        assertTrue(text.contains("도달할 수 없는 노드"), text); // more is unreachable
    }

    @Test
    void rejectsMissingMessageKey() throws IOException {
        write("dialogs/hansol.yml", dialog("goto: more").replace("npc.t.bye", "npc.t.missing"));
        assertTrue(errors().format().contains("npc.t.missing"));
    }

    @Test
    void wandererNeedsSchedule() throws IOException {
        write("dialogs/hansol.yml", dialog("goto: more"));
        write(
                "npcs/extra.yml",
                """
                - id: npc/captain
                  name: 선장
                  role: wanderer
                """);
        assertTrue(errors().format().contains("schedule"));
    }

    @Test
    void cutsceneKeyframesMustIncrease() throws IOException {
        write("dialogs/hansol.yml", dialog("goto: more"));
        write("cutscenes/c.yml", cutscene("[{t: 0, pos: [0, 70, 0]}, {t: 0, pos: [1, 70, 0]}]"));
        assertTrue(errors().format().contains("커져야"));
    }

    @Test
    void realContentFolderLoads() {
        Path content = Path.of("..", "content");
        if (!Files.isDirectory(content.resolve("dialogs"))) {
            return;
        }
        Result<ContentSnapshot, ValidationErrors> result = loader.load(content);
        if (result instanceof Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors e)) {
            // Other modules' types (shops etc.) are not registered, so those references are
            // skipped. Only NPC-side errors count as failures
            String npcErrors =
                    e.list().stream()
                            .map(Object::toString)
                            .filter(
                                    l ->
                                            l.contains("npc")
                                                    || l.contains("dialog")
                                                    || l.contains("cutscene"))
                            .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(npcErrors.isEmpty(), npcErrors);
        } else {
            ContentSnapshot s = ((Result.Ok<ContentSnapshot, ValidationErrors>) result).value();
            assertFalse(s.all(NpcDef.TYPE).isEmpty());
        }
    }

    private ValidationErrors errors() {
        Result<ContentSnapshot, ValidationErrors> result = loader.load(root);
        assertTrue(result instanceof Result.Err, "오류가 있어야 합니다");
        return ((Result.Err<ContentSnapshot, ValidationErrors>) result).error();
    }

    private static String dialog(String startGoto) {
        return """
                - id: dialog/hansol
                  entries:
                    - {conditions: [{affinity_min: {level: 2}}], node: start}
                    - {node: start}
                  nodes:
                    start:
                      pages: [npc.t.hello]
                      options:
                        - {label: npc.t.bye, actions: [{type: flag:set, flag: met}, %s]}
                    more:
                      pages: [npc.t.more, npc.t.hello]
                      options:
                        - {label: npc.t.bye, actions: [close]}
                """
                .formatted(startGoto);
    }

    private static String cutscene(String camera) {
        return """
                - id: cutscene/c
                  world: harbor_main
                  camera: %s
                  actors:
                    - {id: hansol, npc: npc/hansol, path: [{t: 0, pos: [1, 64, 2, 90, 0]}]}
                  lines: [{t: 0, actor: hansol, text: npc.t.line, ticks: 60}]
                """
                .formatted(camera);
    }

    private void write(String file, String text) throws IOException {
        Path path = root.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
