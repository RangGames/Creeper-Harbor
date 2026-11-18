package wiki.creeper.harbor.core.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
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
 * 아이템 정의(YAML) → ItemStack. Paper Data Component API로 item_model·item_name·lore·tooltip_style을 붙이고,
 * Harbor 데이터는 custom_data(PersistentDataContainer)에 넣는다.
 */
public final class ItemManager implements ItemService {

    /** custom_data 형식 버전. 형식을 바꾸면 올리고 {@link #upgrade}에 변환을 추가한다. */
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
        decorate(stack, def.name(), def.grade(), def.model(), lore(def.lore()));
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
        // v0 → v1: 버전 키만 추가 (형식 변경 없음). 이후 버전은 여기에 단계별 변환을 추가한다.
        stack.editPersistentDataContainer(
                pdc -> pdc.set(ItemKeys.VERSION, PersistentDataType.INTEGER, CURRENT_VERSION));
        return true;
    }

    /**
     * 인벤토리의 Harbor 아이템 모델을 지금 설정({@link ItemModels})에 맞춘다 (접속할 때). 붙어 있는 harbor: 모델을 지금 쓸 수 없으면
     * (꺼졌거나 리소스팩에 없음) 떼고, 모델이 없는 아이템에 쓸 수 있는 정의 모델이 있으면 붙인다. 쓸 수 있는 모델(스킨 등)은 그대로 둔다.
     *
     * @return 고친 칸 수
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

    // ------------------------------------------------------------------ 내부

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

    /** 설명 문구 (디자이너가 MiniMessage 서식을 쓸 수 있다). 기울임 기본값을 끈다. */
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
            // 콘텐츠 로더가 미리 검사하므로 여기 오면 코드 오류
            throw new IllegalStateException("바닐라 아이템이 아닙니다: " + key);
        }
        return material;
    }
}
