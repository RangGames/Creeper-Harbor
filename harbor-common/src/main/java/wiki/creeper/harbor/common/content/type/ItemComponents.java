package wiki.creeper.harbor.common.content.type;

import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Per-type vanilla data components of an item definition (items/*.yml {@code components:}, '8.
 * System design' 2, docs 19 5.5). Core applies them with the Paper Data Component API on top of the
 * base item. Every block is optional; a missing block keeps the base item's own component.
 *
 * <pre>
 * components:
 *   consumable: {seconds: 1.6, animation: eat, sound: "minecraft:entity.generic.eat", particles: true}
 *   food: {nutrition: 6, saturation: 7.2, can_always_eat: false}
 *   use_remainder: minecraft:bowl
 *   use_cooldown: {seconds: 30, group: "harbor:return_conch"}
 *   equippable: {slot: head, camera_overlay: "harbor:overlay/diving_helmet", asset_id: "harbor:diving_helmet"}
 *   max_stack: 16
 * </pre>
 *
 * The field names follow the vanilla component names (proposed YAML shape).
 */
public record ItemComponents(
        @Nullable Consumable consumable,
        @Nullable Food food,
        @Nullable String useRemainder,
        @Nullable UseCooldown useCooldown,
        @Nullable Equippable equippable,
        OptionalInt maxStack) {

    /** No overrides. */
    public static final ItemComponents NONE =
            new ItemComponents(null, null, null, null, null, OptionalInt.empty());

    /** Same keys as vanilla ItemUseAnimation. */
    public enum Animation implements Keyed {
        NONE,
        EAT,
        DRINK,
        BLOCK,
        BOW,
        TRIDENT,
        CROSSBOW,
        SPYGLASS,
        TOOT_HORN,
        BRUSH,
        BUNDLE,
        SPEAR;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** Equipment slot of the equippable component. */
    public enum Slot implements Keyed {
        HEAD,
        CHEST,
        LEGS,
        FEET,
        MAINHAND,
        OFFHAND,
        BODY,
        SADDLE;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /**
     * consumable: hold use for {@code seconds}, then consume.
     *
     * @param sound consume sound key (null keeps the vanilla default)
     */
    public record Consumable(
            double seconds, Animation animation, @Nullable String sound, boolean particles) {}

    /** food: hunger and saturation restored. */
    public record Food(int nutrition, double saturation, boolean canAlwaysEat) {}

    /**
     * use_cooldown: cooldown after use, shown on the hotbar.
     *
     * @param group cooldown group key (items in the same group share it; null = per item)
     */
    public record UseCooldown(double seconds, @Nullable String group) {}

    /**
     * equippable.
     *
     * @param cameraOverlay full-screen texture while worn (e.g. harbor:overlay/diving_helmet). Core
     *     sets it only when the item's model is in the served resource pack (a missing texture
     *     covers the screen with the missing-texture pattern)
     * @param assetId equipment model (assets/&lt;ns&gt;/equipment/&lt;id&gt;.json), same rule
     */
    public record Equippable(Slot slot, @Nullable String cameraOverlay, @Nullable String assetId) {}

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final Set<String> FIELDS =
            Set.of(
                    "consumable",
                    "food",
                    "use_remainder",
                    "use_cooldown",
                    "equippable",
                    "max_stack");

    public boolean isEmpty() {
        return equals(NONE);
    }

    /** Parses the {@code components:} block. Errors go to the node's error list. */
    static ItemComponents parse(YamlNode node) {
        node.allowOnly(FIELDS);
        Consumable consumable = node.get("consumable").map(ItemComponents::consumable).orElse(null);
        Food food = node.get("food").map(ItemComponents::food).orElse(null);
        String remainder = null;
        if (node.has("use_remainder")) {
            remainder = key(node, "use_remainder");
        }
        UseCooldown cooldown =
                node.get("use_cooldown").map(ItemComponents::useCooldown).orElse(null);
        Equippable equippable = node.get("equippable").map(ItemComponents::equippable).orElse(null);
        OptionalInt maxStack = OptionalInt.empty();
        if (node.has("max_stack")) {
            maxStack = OptionalInt.of(node.integer("max_stack", 64, 1, 99));
        }
        return new ItemComponents(consumable, food, remainder, cooldown, equippable, maxStack);
    }

    private static Consumable consumable(YamlNode n) {
        n.allowOnly(Set.of("seconds", "animation", "sound", "particles"));
        double seconds = n.number("seconds", 1.6);
        if (seconds < 0 || seconds > 60) {
            n.error("consumable.seconds는 0~60이어야 합니다");
        }
        Animation animation =
                n.get("animation").flatMap(v -> v.asEnum(Animation.class)).orElse(Animation.EAT);
        String sound = n.has("sound") ? key(n, "sound") : null;
        return new Consumable(seconds, animation, sound, n.bool("particles", true));
    }

    private static Food food(YamlNode n) {
        n.allowOnly(Set.of("nutrition", "saturation", "can_always_eat"));
        int nutrition = n.integer("nutrition", 0, 0, 20);
        double saturation = n.number("saturation", 0);
        if (saturation < 0) {
            n.error("food.saturation은 0 이상이어야 합니다");
        }
        return new Food(nutrition, saturation, n.bool("can_always_eat", false));
    }

    private static UseCooldown useCooldown(YamlNode n) {
        n.allowOnly(Set.of("seconds", "group"));
        double seconds = n.number("seconds");
        if (seconds <= 0) {
            n.error("use_cooldown.seconds는 0보다 커야 합니다");
        }
        String group = n.has("group") ? key(n, "group") : null;
        return new UseCooldown(seconds, group);
    }

    private static Equippable equippable(YamlNode n) {
        n.allowOnly(Set.of("slot", "camera_overlay", "asset_id"));
        Slot slot = n.enumValue("slot", Slot.class).orElse(Slot.HEAD);
        String overlay = n.has("camera_overlay") ? key(n, "camera_overlay") : null;
        String asset = n.has("asset_id") ? key(n, "asset_id") : null;
        return new Equippable(slot, overlay, asset);
    }

    /** Namespaced key value (e.g. minecraft:bowl). Records an error for other formats. */
    private static String key(YamlNode n, String field) {
        String value = n.string(field);
        if (!KEY.matcher(value).matches()) {
            n.error(field + "는 'minecraft:bowl' 같은 키여야 합니다: '" + value + "'");
        }
        return value;
    }
}
