package wiki.creeper.harbor.collection.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.collection.api.CodexEntry;
import wiki.creeper.harbor.collection.api.CodexScope;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.PagedMenu;

/**
 * 도감 화면 (/도감, docs 20 4.4). 등록 어종은 어종 모델 아이콘 + 내 기록, 미등록은 실루엣 + 힌트. 누르면 Dialog 상세(설명, 힌트 또는 내 기록,
 * 리그별 서버 1위). 지역 페이지 배경(커스텀 폰트)은 리소스팩이 준비되면 붙인다.
 */
final class CodexMenu {

    private final HarborApi api;
    private final CollectionManager codex;

    CodexMenu(HarborApi api, CollectionManager codex) {
        this.api = api;
        this.codex = codex;
    }

    void open(Player viewer, UUID owner, int page) {
        List<FishDef> fish = new ArrayList<>(api.content().snapshot().all(FishDef.TYPE).values());
        fish.sort(Comparator.comparingInt(FishDef::no));
        MessageService msg = api.messages();
        String percent =
                String.format(Locale.ROOT, "%.1f", codex.percent(owner, CodexScope.SEASON, null));
        PagedMenu<FishDef> menu =
                new PagedMenu<>(
                        msg.get("collection.menu.title", Placeholder.unparsed("percent", percent)),
                        6,
                        fish,
                        f -> new PagedMenu.Entry(icon(owner, f), e -> detail(viewer, owner, f)),
                        named(Material.ARROW, msg.get("collection.menu.previous")),
                        named(Material.ARROW, msg.get("collection.menu.next")));
        menu.show(viewer, page);
    }

    private ItemStack icon(UUID owner, FishDef fish) {
        MessageService msg = api.messages();
        Optional<CodexEntry> entry = codex.entry(owner, fish.id(), CodexScope.TOTAL);
        String baseItem =
                api.content()
                        .snapshot()
                        .single(Balance.TYPE)
                        .string("items.fish_base_item")
                        .orElse("minecraft:cod");
        Material material =
                Optional.ofNullable(Material.matchMaterial(baseItem)).orElse(Material.COD);
        ItemStack stack = ItemStack.of(material);
        List<Component> lore = new ArrayList<>();
        if (entry.isPresent()) {
            ItemModels.apply(stack, fish.model());
            stack.setData(
                    DataComponentTypes.ITEM_NAME,
                    msg.get(
                            "item.name." + fish.grade().key(),
                            Placeholder.unparsed("name", fish.name())));
            lore.add(
                    msg.get(
                            "collection.menu.no",
                            Placeholder.unparsed("no", String.valueOf(fish.no()))));
            lore.add(
                    msg.get(
                            "collection.menu.best",
                            Placeholder.unparsed(
                                    "size", CollectionManager.fmt(entry.get().maxSize())),
                            Placeholder.unparsed(
                                    "quality",
                                    Quality.of(Math.clamp(entry.get().bestQuality(), 1, 5))
                                            .display()),
                            Placeholder.unparsed("count", String.valueOf(entry.get().count()))));
        } else {
            String silhouette =
                    api.content()
                            .snapshot()
                            .single(Balance.TYPE)
                            .string("items.silhouette_model")
                            .orElse("harbor:fish/silhouette");
            ItemModels.apply(stack, silhouette);
            stack.setData(
                    DataComponentTypes.ITEM_NAME,
                    msg.get(
                            "collection.menu.unknown",
                            Placeholder.unparsed("no", String.valueOf(fish.no()))));
            lore.addAll(hints(owner, fish));
        }
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                        lore.stream()
                                .map(
                                        c ->
                                                c.decorationIfAbsent(
                                                        TextDecoration.ITALIC,
                                                        TextDecoration.State.FALSE))
                                .toList()));
        return stack;
    }

    /** 힌트 단계별 공개 (0 지역 → 1 시간대 → 2 날씨·미끼 → 3 구역·달·직업). */
    private List<Component> hints(UUID owner, FishDef fish) {
        MessageService msg = api.messages();
        int level = codex.level(owner, fish.id());
        List<Component> lines = new ArrayList<>();
        lines.add(
                msg.get(
                        "collection.hint.region",
                        Placeholder.unparsed("region", codex.regionName(fish.region()))));
        if (level >= 1) {
            lines.add(
                    msg.get(
                            "collection.hint.time",
                            Placeholder.unparsed("value", list(fish.time()))));
        }
        if (level >= 2) {
            lines.add(
                    msg.get(
                            "collection.hint.weather",
                            Placeholder.unparsed("value", list(fish.weather()))));
            lines.add(
                    msg.get(
                            "collection.hint.bait",
                            Placeholder.unparsed(
                                    "value",
                                    list(fish.bait().stream().map(b -> b.id().name()).toList()))));
        }
        if (level >= 3) {
            lines.add(
                    msg.get(
                            "collection.hint.extra",
                            Placeholder.unparsed("area", list(fish.area())),
                            Placeholder.unparsed("moon", list(fish.moon())),
                            Placeholder.unparsed(
                                    "tier", String.valueOf(Math.max(1, fish.jobTierMin())))));
        }
        return lines;
    }

    private static String list(List<String> values) {
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    private void detail(Player viewer, UUID owner, FishDef fish) {
        MessageService msg = api.messages();
        List<Component> body = new ArrayList<>();
        Optional<CodexEntry> entry = codex.entry(owner, fish.id(), CodexScope.TOTAL);
        if (entry.isPresent()) {
            fish.lore().forEach(line -> body.add(MiniMessage.miniMessage().deserialize(line)));
            body.add(
                    msg.get(
                            "collection.menu.best",
                            Placeholder.unparsed(
                                    "size", CollectionManager.fmt(entry.get().maxSize())),
                            Placeholder.unparsed(
                                    "quality",
                                    Quality.of(Math.clamp(entry.get().bestQuality(), 1, 5))
                                            .display()),
                            Placeholder.unparsed("count", String.valueOf(entry.get().count()))));
            for (String league : List.of("tier1", "tier2", "all")) {
                List<RecordEntry> top = codex.serverTop(fish.id(), league, 1);
                if (!top.isEmpty()) {
                    body.add(
                            msg.get(
                                    "collection.menu.leader",
                                    Placeholder.unparsed("league", league),
                                    Placeholder.unparsed("player", top.getFirst().name()),
                                    Placeholder.unparsed(
                                            "size", CollectionManager.fmt(top.getFirst().size()))));
                }
            }
        } else {
            body.addAll(hints(owner, fish));
        }
        viewer.closeInventory();
        Component title =
                entry.isPresent()
                        ? msg.get(
                                "item.name." + fish.grade().key(),
                                Placeholder.unparsed("name", fish.name()))
                        : msg.get(
                                "collection.menu.unknown",
                                Placeholder.unparsed("no", String.valueOf(fish.no())));
        api.dialogs().notice(viewer, title, body);
    }

    private static ItemStack named(Material material, Component name) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        return stack;
    }
}
