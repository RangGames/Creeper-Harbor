package wiki.creeper.harbor.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.npc.api.DialogStyle;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.NpcContent;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.logic.DialogEval;
import wiki.creeper.harbor.npc.logic.SpeechText;

/** Dialog style (style, dialog_style, auto) parsing and speech bubble text helpers. */
class DialogStyleTest {

    @TempDir Path root;
    ContentLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        loader = new ContentLoader();
        CoreContent.register(loader);
        NpcContent.TYPES.forEach(loader::register);
        write("regions.yml", "[]\n");
        write("balance.yml", "npc: {}\n");
        write("messages_ko.yml", "npc:\n  t:\n    hello: \"안녕\"\n    bye: \"잘 가\"\n");
    }

    // ------------------------------------------------------------------ parsing

    @Test
    void parsesStyleKeys() {
        assertEquals(Optional.of(DialogStyle.HOLOGRAM), DialogStyle.parse("hologram"));
        assertEquals(Optional.of(DialogStyle.ACTIONBAR), DialogStyle.parse(" ActionBar "));
        assertTrue(DialogStyle.parse("bubble").isEmpty());
        assertTrue(DialogStyle.DIALOG.modal());
        assertFalse(DialogStyle.CHAT.modal());
        assertEquals("dialog, hologram, title, chat, actionbar", DialogStyle.keys());
    }

    @Test
    void readsNodeStyleAutoAndNpcDefault() throws IOException {
        write("npcs.yml", npc("dialog_style: chat"));
        write("dialogs/d.yml", dialog("style: hologram\n      auto: 3.5"));
        ContentSnapshot s = loader.load(root).orThrow();
        NpcDef npc = s.get(NpcDef.TYPE, HarborId.parse("npc/a")).orElseThrow();
        assertEquals(DialogStyle.CHAT, npc.dialogStyle());
        DialogDef.Node node =
                s.get(DialogDef.TYPE, HarborId.parse("dialog/d"))
                        .orElseThrow()
                        .nodes()
                        .get("start");
        assertEquals(DialogStyle.HOLOGRAM, node.style());
        assertEquals(3.5, node.autoSeconds());
    }

    @Test
    void stylesAreOptional() throws IOException {
        write("npcs.yml", npc(""));
        write("dialogs/d.yml", dialog(""));
        ContentSnapshot s = loader.load(root).orThrow();
        assertNull(s.get(NpcDef.TYPE, HarborId.parse("npc/a")).orElseThrow().dialogStyle());
        DialogDef.Node node =
                s.get(DialogDef.TYPE, HarborId.parse("dialog/d"))
                        .orElseThrow()
                        .nodes()
                        .get("start");
        assertNull(node.style());
        assertEquals(0, node.autoSeconds());
    }

    @Test
    void rejectsUnknownStyleWithPosition() throws IOException {
        write("npcs.yml", npc("dialog_style: bubble"));
        write("dialogs/d.yml", dialog("style: popup\n      auto: -1"));
        String text = errors().format();
        assertTrue(text.contains("'bubble'"), text);
        assertTrue(text.contains("'popup'"), text);
        assertTrue(text.contains("auto는 0 이상"), text);
        assertTrue(text.contains("npcs.yml"), text); // The position (file) is attached
    }

    // ------------------------------------------------------------------ text helpers

    @Test
    void slicesKeepingStyle() {
        Component text =
                Component.text("안녕 ")
                        .append(Component.text("어부", NamedTextColor.GOLD))
                        .append(Component.text("님!"));
        assertEquals(7, SpeechText.length(text));
        assertEquals("안녕 어", plain(SpeechText.slice(text, 4)));
        assertEquals("안녕 어부님!", plain(SpeechText.slice(text, 100)));
        assertEquals("", plain(SpeechText.slice(text, 0)));
        Component cut = SpeechText.slice(text, 4);
        assertEquals(NamedTextColor.GOLD, cut.children().getFirst().color()); // Color kept
    }

    @Test
    void countsSurrogatePairsAsOneChar() {
        Component fish = Component.text("🐟물고기");
        assertEquals(4, SpeechText.length(fish));
        assertEquals("🐟", plain(SpeechText.slice(fish, 1)));
    }

    @Test
    void typewriterAndAutoTicks() {
        assertEquals(0, SpeechText.typewriterTicks(10, 0));
        assertEquals(5, SpeechText.typewriterTicks(10, 2));
        assertEquals(4, SpeechText.typewriterTicks(10, 3));
        assertEquals(0, SpeechText.autoTicks(0));
        assertEquals(70, SpeechText.autoTicks(3.5));
    }

    @Test
    void speechPageFlow() {
        // With 3 pages, choices only on the last page; with no pages, choices immediately
        assertFalse(DialogEval.optionsOnPage(3, 0));
        assertFalse(DialogEval.optionsOnPage(3, 1));
        assertTrue(DialogEval.optionsOnPage(3, 2));
        assertTrue(DialogEval.optionsOnPage(0, 0));
        assertEquals(2, DialogEval.clampPage(3, 9));
        assertEquals(0, DialogEval.clampPage(0, 5));
    }

    // ------------------------------------------------------------------ helpers

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static String npc(String extra) {
        return """
                - id: npc/a
                  name: 가
                  role: story
                  location: {world: harbor_main, x: 1, y: 64, z: 2}
                  dialog: dialog/d
                  %s
                """
                .formatted(extra);
    }

    private static String dialog(String nodeExtra) {
        return """
                - id: dialog/d
                  entries:
                    - {node: start}
                  nodes:
                    start:
                      pages: [npc.t.hello]
                      %s
                      options:
                        - {label: npc.t.bye, actions: [close]}
                """
                .formatted(nodeExtra);
    }

    private ValidationErrors errors() {
        Result<ContentSnapshot, ValidationErrors> result = loader.load(root);
        assertTrue(result instanceof Result.Err, "오류가 있어야 합니다");
        return ((Result.Err<ContentSnapshot, ValidationErrors>) result).error();
    }

    private void write(String name, String text) throws IOException {
        Path p = root.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }
}
