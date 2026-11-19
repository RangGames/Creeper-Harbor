package wiki.creeper.harbor.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.npc.content.Skin;

class SkinTest {

    private static Optional<Skin> parse(String yaml, ValidationErrors errors) {
        YamlNode root = YamlNode.parse("npcs.yml", new StringReader(yaml), errors).orElseThrow();
        return Skin.parse(root.get("skin").orElseThrow());
    }

    private static String decoded(Skin skin) {
        return new String(
                Base64.getDecoder().decode(String.valueOf(skin.value())), StandardCharsets.UTF_8);
    }

    @Test
    void shortFormsByShape() {
        ValidationErrors errors = new ValidationErrors();
        Skin pack = parse("skin: harbor:entity/npc/hansol", errors).orElseThrow();
        assertEquals("harbor:entity/npc/hansol", pack.texture());

        Skin nick = parse("skin: Notch", errors).orElseThrow();
        assertEquals("Notch", nick.player());
        assertNull(nick.texture());

        Skin url =
                parse(
                                "skin: https://textures.minecraft.net/texture/abc123def456abc123def456abc123de",
                                errors)
                        .orElseThrow();
        assertTrue(decoded(url).contains("http://textures.minecraft.net/texture/abc123"));
        assertTrue(errors.isEmpty(), errors.format());
    }

    @Test
    void mapForms() {
        ValidationErrors errors = new ValidationErrors();
        Skin slimUrl =
                parse("skin: {hash: ABC123DEF456ABC123DEF456ABC123DE, model: slim}", errors)
                        .orElseThrow();
        assertTrue(decoded(slimUrl).contains("\"model\":\"slim\""));
        Skin player = parse("skin: {player: jeb_}", errors).orElseThrow();
        assertEquals("jeb_", player.player());
        Skin value = parse("skin: {value: dGVzdA==}", errors).orElseThrow();
        assertEquals("dGVzdA==", value.value());
        assertNull(value.signature());
        assertTrue(errors.isEmpty(), errors.format());
    }

    @Test
    void rejectsBadInput() {
        ValidationErrors errors = new ValidationErrors();
        assertTrue(parse("skin: https://example.com/skin.png", errors).isEmpty());
        assertTrue(parse("skin: {player: Notch, texture: harbor:a/b}", errors).isEmpty());
        assertTrue(parse("skin: '한솔 스킨'", errors).isEmpty());
        assertFalse(errors.isEmpty());
        assertEquals(3, errors.size());
    }
}
