package wiki.creeper.harbor.common.content.type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

class ItemComponentsTest {

    private static ItemDef parse(String yaml, ValidationErrors errors) {
        YamlNode node = YamlNode.parse("items/x.yml", new StringReader(yaml), errors).orElseThrow();
        List<YamlNode> entries = node.asList();
        return ItemDef.parse(entries.getFirst(), new ParseContext());
    }

    private static final String BASE =
            """
            - id: item/food/test
              name: 시험
              type: food
              grade: common
              base_item: minecraft:mushroom_stew
              model: harbor:food/test
              tradeable: true
              season_reset: true
            """;

    @Test
    void noBlockMeansNone() {
        ValidationErrors errors = new ValidationErrors();
        ItemDef def = parse(BASE, errors);
        assertTrue(errors.isEmpty(), errors.format());
        assertSame(ItemComponents.NONE, def.components());
        assertTrue(def.components().isEmpty());
    }

    @Test
    void readsAllBlocks() {
        ValidationErrors errors = new ValidationErrors();
        ItemDef def =
                parse(
                        BASE
                                + """
                                  components:
                                    consumable: {seconds: 2.0, animation: drink, sound: "minecraft:item.honey_bottle.drink"}
                                    food: {nutrition: 6, saturation: 7.2, can_always_eat: true}
                                    use_remainder: minecraft:bowl
                                    use_cooldown: {seconds: 30, group: "harbor:return_conch"}
                                    equippable: {slot: head, camera_overlay: "harbor:overlay/diving_helmet"}
                                    max_stack: 16
                                """,
                        errors);
        assertTrue(errors.isEmpty(), errors.format());
        ItemComponents c = def.components();
        assertFalse(c.isEmpty());
        ItemComponents.Consumable consumable = c.consumable();
        assertNotNull(consumable);
        assertEquals(2.0, consumable.seconds(), 1e-9);
        assertEquals(ItemComponents.Animation.DRINK, consumable.animation());
        assertEquals("minecraft:item.honey_bottle.drink", consumable.sound());
        assertTrue(consumable.particles());
        ItemComponents.Food food = c.food();
        assertNotNull(food);
        assertEquals(6, food.nutrition());
        assertTrue(food.canAlwaysEat());
        assertEquals("minecraft:bowl", c.useRemainder());
        ItemComponents.UseCooldown cooldown = c.useCooldown();
        assertNotNull(cooldown);
        assertEquals("harbor:return_conch", cooldown.group());
        ItemComponents.Equippable equippable = c.equippable();
        assertNotNull(equippable);
        assertEquals(ItemComponents.Slot.HEAD, equippable.slot());
        assertEquals("harbor:overlay/diving_helmet", equippable.cameraOverlay());
        assertNull(equippable.assetId());
        assertEquals(16, c.maxStack().orElseThrow());
    }

    @Test
    void rejectsBadValues() {
        ValidationErrors errors = new ValidationErrors();
        parse(
                BASE
                        + """
                          components:
                            consumable: {animation: dance}
                            use_remainder: bowl
                            use_cooldown: {seconds: 0}
                            equippable: {camera_overlay: "x"}
                            max_stack: 200
                            unknown: 1
                        """,
                errors);
        // animation, use_remainder, use_cooldown.seconds, equippable.slot missing,
        // camera_overlay, max_stack, unknown field
        assertEquals(7, errors.size(), errors.format());
    }
}
