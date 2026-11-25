package wiki.creeper.harbor.fishing.rod;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.fishing.content.RodSettings;

/**
 * Rod attachments and rod-only enchants stored in the rod's PDC ("8. System design" 2: line, float
 * and reel attachment info, one item ID per slot; 6: rod-only enchants as a level per enchant ID).
 * The rod's lore lists the attached parts and enchants. Main thread only.
 */
public final class RodParts {

    /** Attachment slots (the item types that can be attached). */
    public static final List<ItemDef.Type> SLOTS =
            List.of(ItemDef.Type.LINE, ItemDef.Type.FLOAT, ItemDef.Type.REEL);

    private final HarborApi api;
    private final Map<ItemDef.Type, NamespacedKey> partKeys = new EnumMap<>(ItemDef.Type.class);
    private final Plugin plugin;

    public RodParts(Plugin plugin, HarborApi api) {
        this.plugin = plugin;
        this.api = api;
        for (ItemDef.Type slot : SLOTS) {
            partKeys.put(slot, new NamespacedKey(plugin, "rod_" + slot.key()));
        }
    }

    /** Whether the stack is a fishing rod (vanilla or Harbor). */
    public static boolean isRod(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getType() == Material.FISHING_ROD;
    }

    public RodSettings settings() {
        return api.content().snapshot().single(RodSettings.TYPE);
    }

    // ------------------------------------------------------------------ Read

    /** Attached part per slot (only parts that still exist in the content). */
    public Map<ItemDef.Type, ItemDef> parts(ItemStack rod) {
        Map<ItemDef.Type, ItemDef> result = new EnumMap<>(ItemDef.Type.class);
        if (!isRod(rod)) {
            return result;
        }
        ContentSnapshot snapshot = api.content().snapshot();
        PersistentDataContainerView pdc = rod.getPersistentDataContainer();
        for (ItemDef.Type slot : SLOTS) {
            String raw = pdc.get(partKey(slot), PersistentDataType.STRING);
            if (raw == null) {
                continue;
            }
            HarborId.tryParse(raw)
                    .flatMap(id -> snapshot.get(ItemDef.TYPE, id))
                    .filter(d -> d.type() == slot)
                    .ifPresent(d -> result.put(slot, d));
        }
        return result;
    }

    /** Raw attached part ID of a slot (also when the definition was removed). */
    public Optional<ItemId> partId(ItemStack rod, ItemDef.Type slot) {
        if (!isRod(rod)) {
            return Optional.empty();
        }
        String raw = rod.getPersistentDataContainer().get(partKey(slot), PersistentDataType.STRING);
        return raw == null ? Optional.empty() : HarborId.tryParse(raw).map(ItemId::new);
    }

    /** Enchant levels (file order of fishing/rod.yml, only levels above 0). */
    public Map<RodSettings.Enchant, Integer> enchants(ItemStack rod) {
        Map<RodSettings.Enchant, Integer> result = new LinkedHashMap<>();
        if (!isRod(rod)) {
            return result;
        }
        for (RodSettings.Enchant e : settings().enchants().values()) {
            int level = level(rod, e.id());
            if (level > 0) {
                result.put(e, Math.min(level, e.maxLevel()));
            }
        }
        return result;
    }

    public int level(ItemStack rod, String enchant) {
        if (!isRod(rod)) {
            return 0;
        }
        Integer level =
                rod.getPersistentDataContainer()
                        .get(enchantKey(enchant), PersistentDataType.INTEGER);
        return level == null ? 0 : Math.max(0, level);
    }

    /** Stats of the attached parts and enchants (added to the rod's own item stats). */
    public Map<String, Double> stats(ItemStack rod) {
        Map<String, Double> sum = new HashMap<>();
        if (!isRod(rod)) {
            return sum;
        }
        for (ItemDef part : parts(rod).values()) {
            part.stats().forEach((k, v) -> sum.merge(k, v, Double::sum));
        }
        enchants(rod).forEach((e, level) -> sum.merge(e.stat(), e.value(level), Double::sum));
        return sum;
    }

    // ------------------------------------------------------------------ Write

    /** Sets or clears a slot and refreshes the lore. */
    public void setPart(ItemStack rod, ItemDef.Type slot, @Nullable ItemId part) {
        NamespacedKey key = partKey(slot);
        rod.editPersistentDataContainer(
                pdc -> {
                    if (part == null) {
                        pdc.remove(key);
                    } else {
                        pdc.set(key, PersistentDataType.STRING, part.toString());
                    }
                });
        refreshLore(rod);
    }

    /** Sets an enchant level and refreshes the lore and glint. */
    public void setLevel(ItemStack rod, String enchant, int level) {
        NamespacedKey key = enchantKey(enchant);
        rod.editPersistentDataContainer(
                pdc -> {
                    if (level <= 0) {
                        pdc.remove(key);
                    } else {
                        pdc.set(key, PersistentDataType.INTEGER, level);
                    }
                });
        refreshLore(rod);
    }

    /**
     * Rebuilds the lore: the definition's lore (Harbor rods) followed by the enchant and part
     * lines. Adds the enchant glint while any enchant is applied.
     */
    public void refreshLore(ItemStack rod) {
        MessageService msg = api.messages();
        List<Component> lore = new ArrayList<>(baseLore(rod));
        Map<RodSettings.Enchant, Integer> enchants = enchants(rod);
        Map<ItemDef.Type, ItemDef> parts = parts(rod);
        if (!enchants.isEmpty() || !parts.isEmpty()) {
            lore.add(Component.empty());
        }
        enchants.forEach(
                (e, level) ->
                        lore.add(
                                line(
                                        msg.get(
                                                "fishing.rod.lore.enchant",
                                                Placeholder.component("name", enchantName(e)),
                                                Placeholder.component(
                                                        "level", levelName(level))))));
        parts.forEach(
                (slot, def) ->
                        lore.add(
                                line(
                                        msg.get(
                                                "fishing.rod.lore.part",
                                                Placeholder.component("slot", slotName(slot)),
                                                Placeholder.component("name", itemName(def))))));
        rod.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        if (enchants.isEmpty()) {
            rod.resetData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
        } else {
            rod.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
    }

    // ------------------------------------------------------------------ Names

    public Component enchantName(RodSettings.Enchant e) {
        return api.messages().get("fishing.rod.enchant.name." + e.id());
    }

    public Component levelName(int level) {
        return api.messages().get("fishing.rod.enchant.level.l" + level);
    }

    public Component slotName(ItemDef.Type slot) {
        return api.messages().get("fishing.rod.slot." + slot.key());
    }

    public Component itemName(ItemDef def) {
        return api.messages()
                .get("item.name." + def.grade().key(), Placeholder.unparsed("name", def.name()));
    }

    /** Harbor rod definition of the stack, if any. */
    public Optional<ItemDef> rodDef(ItemStack rod) {
        return api.items()
                .read(rod)
                .filter(i -> i instanceof HarborItem.Plain)
                .map(i -> (HarborItem.Plain) i)
                .flatMap(p -> api.content().snapshot().get(ItemDef.TYPE, p.id().id()));
    }

    // ------------------------------------------------------------------ Internal

    private List<Component> baseLore(ItemStack rod) {
        Optional<ItemDef> def = rodDef(rod);
        if (def.isEmpty()) {
            return List.of();
        }
        ItemLore lore = api.items().create(def.get(), 1).getData(DataComponentTypes.LORE);
        return lore == null ? List.of() : lore.lines();
    }

    private static Component line(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private NamespacedKey partKey(ItemDef.Type slot) {
        NamespacedKey key = partKeys.get(slot);
        if (key == null) {
            throw new IllegalArgumentException("부품 자리가 아닙니다: " + slot);
        }
        return key;
    }

    private NamespacedKey enchantKey(String enchant) {
        return new NamespacedKey(plugin, "rod_enchant_" + enchant);
    }
}
