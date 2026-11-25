package wiki.creeper.harbor.core.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.ItemService;
import wiki.creeper.harbor.core.api.MessageService;

/**
 * Item definition (YAML) → ItemStack. Attaches item_model, item_name, lore and tooltip_style via
 * the Paper Data Component API, plus the definition's per-type components (consumable, food,
 * use_remainder, use_cooldown, equippable, max_stack; {@link ItemComponentApplier}), and stores
 * Harbor data in custom_data (PersistentDataContainer).
 */
public final class ItemManager implements ItemService {

    /**
     * custom_data format version. Bump it when the format changes and add a conversion to {@link
     * #upgrade}.
     */
    public static final int CURRENT_VERSION = 1;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

    private final Supplier<ContentSnapshot> content;
    private final MessageService messages;
    private final Database db;
    private final SeasonId season;
    private final ZoneId zone;
    private final Logger log;

    public ItemManager(
            Supplier<ContentSnapshot> content,
            MessageService messages,
            Database db,
            SeasonId season,
            ZoneId zone,
            Logger log) {
        this.content = content;
        this.messages = messages;
        this.db = db;
        this.season = season;
        this.zone = zone;
        this.log = log;
    }

    @Override
    public ItemStack create(ItemDef def, int amount) {
        ItemStack stack = ItemStack.of(material(def.baseItem()), amount);
        decorate(stack, def.name(), def.grade(), def.model(), baitLines(def, lore(def.lore())));
        ItemComponentApplier.apply(stack, def, content.get());
        stack.editPersistentDataContainer(
                pdc -> {
                    pdc.set(ItemKeys.ID, PersistentDataType.STRING, def.id().toString());
                    pdc.set(ItemKeys.VERSION, PersistentDataType.INTEGER, CURRENT_VERSION);
                });
        return stack;
    }

    @Override
    public ItemStack createFish(
            FishDef def, double sizeCm, Quality quality, PlayerRef catcher, Instant caughtAt) {
        double size = Math.round(sizeCm * 10) / 10.0;
        boolean unique = quality.isMax() || def.grade().isAtLeast(Grade.LEGENDARY);
        @Nullable UUID uid = unique ? UUID.randomUUID() : null;
        Balance balance = content.get().single(Balance.TYPE);

        ItemStack stack =
                ItemStack.of(
                        material(balance.string("items.fish_base_item").orElse("minecraft:cod")),
                        1);
        List<Component> lore = lore(def.lore());
        lore.add(line("item.fish.quality", Placeholder.unparsed("quality", quality.display())));
        lore.add(
                line(
                        "item.fish.size",
                        Placeholder.unparsed("size", String.format(Locale.ROOT, "%.1f", size))));
        lore.add(line("item.fish.catcher", Placeholder.unparsed("catcher", catcher.name())));
        lore.add(
                line(
                        "item.fish.date",
                        Placeholder.unparsed("date", DATE.format(caughtAt.atZone(zone)))));
        decorate(stack, def.name(), def.grade(), def.model(), lore);
        // Item model hook ("4. Fishing presentation" 1.3, proposed): custom_model_data float 0 =
        // size within the species range (0~1), float 1 = quality stars. The resource pack picks or
        // scales the model with range_dispatch so bigger fish look bigger
        double span = def.sizeMax() - def.sizeMin();
        float ratio = span <= 0 ? 0.5f : (float) Math.clamp((size - def.sizeMin()) / span, 0, 1);
        stack.setData(
                DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData()
                        .addFloat(ratio)
                        .addFloat(quality.stars())
                        .build());
        if (def.grade().isAtLeast(Grade.LEGENDARY)) {
            stack.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        stack.editPersistentDataContainer(
                pdc -> {
                    pdc.set(ItemKeys.ID, PersistentDataType.STRING, def.id().toString());
                    pdc.set(ItemKeys.VERSION, PersistentDataType.INTEGER, CURRENT_VERSION);
                    pdc.set(ItemKeys.SPECIES, PersistentDataType.STRING, def.id().toString());
                    pdc.set(ItemKeys.SIZE, PersistentDataType.DOUBLE, size);
                    pdc.set(ItemKeys.QUALITY, PersistentDataType.INTEGER, quality.stars());
                    pdc.set(ItemKeys.CATCHER, PersistentDataType.STRING, catcher.uuid().toString());
                    pdc.set(ItemKeys.CATCHER_NAME, PersistentDataType.STRING, catcher.name());
                    pdc.set(ItemKeys.SEASON, PersistentDataType.INTEGER, season.value());
                    pdc.set(ItemKeys.CAUGHT_AT, PersistentDataType.LONG, caughtAt.toEpochMilli());
                    if (uid != null) {
                        pdc.set(ItemKeys.UID, PersistentDataType.STRING, uid.toString());
                    }
                });
        if (uid != null) {
            HarborItem.Fish fish =
                    new HarborItem.Fish(
                            def.id(),
                            size,
                            quality,
                            catcher,
                            season,
                            caughtAt,
                            uid,
                            CURRENT_VERSION);
            var _ =
                    db.asyncVoid(c -> FishLedger.insert(c, uid, fish))
                            .exceptionally(
                                    e -> {
                                        log.error("고유 ID 장부 기록 실패: {} {}", def.id(), uid, e);
                                        return null;
                                    });
        }
        return stack;
    }

    @Override
    public Optional<HarborItem> read(ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        PersistentDataContainerView pdc = stack.getPersistentDataContainer();
        String rawId = pdc.get(ItemKeys.ID, PersistentDataType.STRING);
        if (rawId == null) {
            return Optional.empty();
        }
        Integer storedVersion = pdc.get(ItemKeys.VERSION, PersistentDataType.INTEGER);
        int version = storedVersion == null ? 0 : storedVersion;
        try {
            HarborId id = HarborId.parse(rawId);
            if (!id.hasPrefix(FishId.PREFIX)) {
                return Optional.of(new HarborItem.Plain(new ItemId(id), version));
            }
            Double size = pdc.get(ItemKeys.SIZE, PersistentDataType.DOUBLE);
            Integer quality = pdc.get(ItemKeys.QUALITY, PersistentDataType.INTEGER);
            String catcher = pdc.get(ItemKeys.CATCHER, PersistentDataType.STRING);
            String catcherName = pdc.get(ItemKeys.CATCHER_NAME, PersistentDataType.STRING);
            Integer fishSeason = pdc.get(ItemKeys.SEASON, PersistentDataType.INTEGER);
            Long caughtAt = pdc.get(ItemKeys.CAUGHT_AT, PersistentDataType.LONG);
            String uid = pdc.get(ItemKeys.UID, PersistentDataType.STRING);
            if (size == null
                    || quality == null
                    || catcher == null
                    || fishSeason == null
                    || caughtAt == null) {
                log.warn("어종 아이템 데이터가 빠졌습니다: {}", rawId);
                return Optional.empty();
            }
            return Optional.of(
                    new HarborItem.Fish(
                            new FishId(id),
                            size,
                            Quality.of(quality),
                            new PlayerRef(
                                    UUID.fromString(catcher),
                                    catcherName == null ? "?" : catcherName),
                            new SeasonId(fishSeason),
                            Instant.ofEpochMilli(caughtAt),
                            uid == null ? null : UUID.fromString(uid),
                            version));
        } catch (IllegalArgumentException e) {
            log.warn("잘못된 Harbor 아이템 데이터: {} ({})", rawId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean upgrade(ItemStack stack) {
        Optional<HarborItem> item = read(stack);
        if (item.isEmpty() || item.get().version() >= CURRENT_VERSION) {
            return false;
        }
        // v0 → v1: only adds the version key (no format change). Add step-by-step conversions for
        // later versions here.
        stack.editPersistentDataContainer(
                pdc -> pdc.set(ItemKeys.VERSION, PersistentDataType.INTEGER, CURRENT_VERSION));
        return true;
    }

    /**
     * Aligns Harbor item models in an inventory with the current settings ({@link ItemModels}) (on
     * login). Removes an attached harbor: model that cannot be used now (disabled or missing from
     * the resource pack), and attaches the definition's model to items without one if it is usable.
     * Usable models (skins etc.) are left as is.
     *
     * @return number of slots changed
     */
    public int syncModels(Inventory inventory) {
        ContentSnapshot snapshot = content.get();
        int changed = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<HarborItem> item = read(stack);
            if (item.isEmpty()) {
                continue;
            }
            boolean fixed = false;
            if (stack.isDataOverridden(DataComponentTypes.ITEM_MODEL)) {
                Key current = stack.getData(DataComponentTypes.ITEM_MODEL);
                if (current != null
                        && current.namespace().equals("harbor")
                        && !ItemModels.available(snapshot, current.asString())) {
                    stack.resetData(DataComponentTypes.ITEM_MODEL);
                    fixed = true;
                }
            } else {
                fixed = ItemModels.apply(stack, modelOf(snapshot, item.get()), snapshot);
            }
            if (fixed) {
                inventory.setItem(slot, stack);
                changed++;
            }
        }
        return changed;
    }

    private static @Nullable String modelOf(ContentSnapshot snapshot, HarborItem item) {
        return switch (item) {
            case HarborItem.Plain plain ->
                    snapshot.get(ItemDef.TYPE, plain.id().id()).map(ItemDef::model).orElse(null);
            case HarborItem.Fish fish ->
                    snapshot.get(FishDef.TYPE, fish.species().id())
                            .map(FishDef::model)
                            .orElse(null);
        };
    }

    // ------------------------------------------------------------------ Internal

    private void decorate(
            ItemStack stack, String name, Grade grade, String model, List<Component> lore) {
        stack.setData(
                DataComponentTypes.ITEM_NAME,
                messages.get("item.name." + grade.key(), Placeholder.unparsed("name", name)));
        ContentSnapshot snapshot = content.get();
        Balance balance = snapshot.single(Balance.TYPE);
        ItemModels.apply(stack, model, snapshot);
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        balance.string("items.tooltip_style." + grade.key())
                .ifPresent(
                        style -> stack.setData(DataComponentTypes.TOOLTIP_STYLE, Key.key(style)));
    }

    /**
     * Effect lines of bait items from their stats (messages item.stat.&lt;key&gt;, in key order;
     * keys without a message are not shown) and the usage hint (proposed).
     */
    private List<Component> baitLines(ItemDef def, List<Component> lore) {
        if (def.type() != ItemDef.Type.BAIT) {
            return lore;
        }
        for (var e : new java.util.TreeMap<>(def.stats()).entrySet()) {
            String key = "item.stat." + e.getKey();
            if (!messages.has(key)) {
                continue;
            }
            double v = e.getValue();
            String percent = String.format(Locale.ROOT, "%+.0f", v * 100).replace("-", "−");
            String value =
                    v == Math.rint(v)
                            ? String.valueOf((long) v)
                            : String.format(Locale.ROOT, "%.2f", v);
            lore.add(
                    line(
                            key,
                            TagResolver.resolver(
                                    Placeholder.unparsed("percent", percent),
                                    Placeholder.unparsed("value", value))));
        }
        lore.add(line("item.bait.hint", TagResolver.empty()));
        return lore;
    }

    /** Lore text (designers may use MiniMessage formatting). Turns off the default italics. */
    private static List<Component> lore(List<String> lines) {
        List<Component> result = new ArrayList<>();
        for (String raw : lines) {
            result.add(
                    MiniMessage.miniMessage()
                            .deserialize(raw)
                            .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }
        return result;
    }

    private Component line(String key, TagResolver resolver) {
        return messages.get(key, resolver)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static Material material(String key) {
        Material material = Material.matchMaterial(key);
        if (material == null) {
            // The content loader validates this beforehand, so reaching here is a code bug
            throw new IllegalStateException("바닐라 아이템이 아닙니다: " + key);
        }
        return material;
    }
}
