package wiki.creeper.harbor.common.content.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

class FurnishingDefsTest {

    private static YamlNode node(String yaml, ValidationErrors errors) {
        return YamlNode.parse("x.yml", new StringReader(yaml), errors).orElseThrow();
    }

    @Test
    void blockStatesCoverAllInstrumentsWithoutVanilla() {
        Set<String> seen = new HashSet<>();
        for (int state = 1; state <= CustomBlockDef.MAX_STATE; state++) {
            assertTrue(seen.add(CustomBlockDef.properties(state, false)));
        }
        assertFalse(seen.contains("instrument=harp,note=0,powered=false"));
        assertEquals(
                "instrument=pling,note=24,powered=false", CustomBlockDef.properties(399, false));
        assertEquals(
                "instrument=basedrum,note=0,powered=true", CustomBlockDef.properties(25, true));
    }

    @Test
    void parsesBlockShapes() {
        ValidationErrors errors = new ValidationErrors();
        CustomBlockDef tile =
                CustomBlockDef.parse(
                        node(
                                "{id: block/marble_tile, name: 대리석, state: 1, texture: \"harbor:block/marble\"}",
                                errors),
                        new ParseContext());
        assertEquals("harbor:generated/block/marble_tile", tile.blockModel());
        assertEquals("harbor:generated/block/marble_tile", tile.itemModel());
        CustomBlockDef post =
                CustomBlockDef.parse(
                        node(
                                "{id: block/post, name: 기둥, state: 2, model: \"harbor:block/post\"}",
                                errors),
                        new ParseContext());
        assertEquals("harbor:block/post", post.blockModel());
        CustomBlockDef dice =
                CustomBlockDef.parse(
                        node(
                                "{id: block/dice, name: 주사위, state: 5, textures: {down: \"harbor:b/1\", up: \"harbor:b/6\","
                                        + " north: \"harbor:b/2\", south: \"harbor:b/5\", east: \"harbor:b/3\", west: \"harbor:b/4\"}}",
                                errors),
                        new ParseContext());
        assertEquals("harbor:b/6", dice.faces().get("up"));
        assertEquals(6, dice.faces().size());
        assertTrue(errors.isEmpty(), errors.format());
    }

    @Test
    void rejectsBadBlocks() {
        ValidationErrors errors = new ValidationErrors();
        CustomBlockDef.parse(
                node("{id: block/a, name: A, state: 0, texture: \"harbor:block/a\"}", errors),
                new ParseContext());
        CustomBlockDef.parse(
                node(
                        "{id: block/b, name: B, state: 3, texture: \"harbor:block/b\", model: \"harbor:block/b\"}",
                        errors),
                new ParseContext());
        CustomBlockDef.parse(
                node("{id: block/c, name: C, state: 4, side: \"harbor:block/c\"}", errors),
                new ParseContext());
        assertEquals(3, errors.size(), errors.format());
    }

    @Test
    void parsesFurniture() {
        ValidationErrors errors = new ValidationErrors();
        FurnitureDef chair =
                FurnitureDef.parse(
                        node(
                                "{id: furniture/chair, name: 의자, model: \"harbor:f/chair\", seat: 0.45, solid: true, height: 1.2}",
                                errors),
                        new ParseContext());
        assertEquals(0.45, chair.seat());
        assertEquals(2, chair.solidCells());
        assertEquals(8, chair.rotation());
        FurnitureDef lamp =
                FurnitureDef.parse(
                        node(
                                "{id: furniture/lamp, name: 등, model: \"harbor:f/lamp\", light: 12, rotation: 4}",
                                errors),
                        new ParseContext());
        assertNull(lamp.seat());
        assertEquals(0, lamp.solidCells());
        assertTrue(errors.isEmpty(), errors.format());
        FurnitureDef.parse(
                node("{id: furniture/x, name: X, model: \"harbor:f/x\", rotation: 5}", errors),
                new ParseContext());
        assertEquals(1, errors.size());
    }

    @Test
    void parsesBreakingDropsAndRotation() {
        ValidationErrors errors = new ValidationErrors();
        CustomBlockDef ore =
                CustomBlockDef.parse(
                        node(
                                """
                                id: block/coral_ore
                                name: 산호 광석
                                state: 30
                                texture: "harbor:block/coral_ore"
                                rotation: facing
                                hardness: 3
                                tool: pickaxe
                                tier: iron
                                requires_tool: true
                                drops:
                                  - {item: minecraft:prismarine_shard, min: 1, max: 3}
                                  - {item: item/material/coral_piece, chance: 20}
                                silk_touch: true
                                exp: {min: 1, max: 3}
                                sounds: {break: "minecraft:block.stone.break"}
                                """,
                                errors),
                        new ParseContext());
        assertTrue(errors.isEmpty(), errors.format());
        assertEquals(List.of(30, 31, 32, 33, 34, 35), ore.states());
        assertEquals(34, ore.stateOf("up"));
        assertEquals(CustomBlockDef.Tier.IRON, ore.breaking().tier());
        assertFalse(ore.breaking().vanilla());
        assertEquals(2, ore.drops().size());
        assertEquals("harbor:item/material/coral_piece", ore.drops().get(1).item());
        assertEquals(3, ore.expMax());
        assertNull(ore.placeSound());

        CustomBlockDef plain =
                CustomBlockDef.parse(
                        node(
                                "{id: block/p, name: P, state: 1, texture: \"harbor:block/p\"}",
                                errors),
                        new ParseContext());
        assertTrue(plain.breaking().vanilla());
        assertEquals(List.of(1), plain.states());

        CustomBlockDef.parse(
                node(
                        "{id: block/q, name: Q, state: 398, texture: \"harbor:block/q\", rotation: axis, tier: iron}",
                        errors),
                new ParseContext());
        assertEquals(2, errors.size(), errors.format());
    }
}
