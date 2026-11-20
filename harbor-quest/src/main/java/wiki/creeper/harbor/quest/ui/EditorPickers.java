package wiki.creeper.harbor.quest.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.npc.content.CutsceneDef;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.quest.content.QuestDef;

/** 편집기 선택 목록 (상자 GUI, 페이지). 콘텐츠 스냅샷에서 어종·아이템·NPC·지역·퀘스트·컷씬·등급을 아이콘으로 보여주고 고른 값(ID 글)을 돌려준다. */
final class EditorPickers {

    /** 고를 수 있는 것 하나. value는 YAML에 쓸 글. */
    record Option(ItemStack icon, String value) {}

    private static final int PER_PAGE = 45;

    private final HarborApi api;

    EditorPickers(HarborApi api) {
        this.api = api;
    }

    private MessageService msg() {
        return api.messages();
    }

    private ContentSnapshot snapshot() {
        return api.content().snapshot();
    }

    void pick(
            Player player,
            Component title,
            List<Option> options,
            Consumer<String> onPick,
            Runnable back) {
        show(player, title, options, onPick, back, 0);
    }

    private void show(
            Player player,
            Component title,
            List<Option> options,
            Consumer<String> onPick,
            Runnable back,
            int page) {
        int pages = Math.max(1, (options.size() + PER_PAGE - 1) / PER_PAGE);
        int shown = Math.clamp(page, 0, pages - 1);
        Menu menu = new Menu(title, 6);
        int start = shown * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < options.size(); i++) {
            Option o = options.get(start + i);
            menu.button(i, o.icon(), e -> onPick.accept(o.value()));
        }
        for (int i = 45; i < 54; i++) {
            menu.button(i, MenuIcons.filler(), null);
        }
        menu.button(
                45,
                MenuIcons.icon(Material.ARROW, msg().get("quest.dialog.back")),
                e -> back.run());
        if (shown > 0) {
            menu.button(
                    48,
                    MenuIcons.icon(Material.ARROW, msg().get("quest.menu.previous")),
                    e -> show(player, title, options, onPick, back, shown - 1));
        }
        if (shown < pages - 1) {
            menu.button(
                    50,
                    MenuIcons.icon(Material.ARROW, msg().get("quest.menu.next")),
                    e -> show(player, title, options, onPick, back, shown + 1));
        }
        menu.open(player);
    }

    // ------------------------------------------------------------------ 목록

    private Option option(Material material, String label, String value) {
        return new Option(
                MenuIcons.icon(
                        material,
                        msg().get("quest.editor.pick.label", Placeholder.unparsed("label", label)),
                        List.of(
                                msg().get(
                                                "quest.editor.pick.value",
                                                Placeholder.unparsed("value", value))),
                        false),
                value);
    }

    List<Option> fish() {
        String baseItem =
                snapshot()
                        .single(Balance.TYPE)
                        .string("items.fish_base_item")
                        .orElse("minecraft:cod");
        Material base = Optional.ofNullable(Material.matchMaterial(baseItem)).orElse(Material.COD);
        List<Option> out = new ArrayList<>();
        snapshot().all(FishDef.TYPE).values().stream()
                .sorted(Comparator.comparingInt(FishDef::no))
                .forEach(
                        f -> {
                            Option o =
                                    option(
                                            base,
                                            "No."
                                                    + f.no()
                                                    + " "
                                                    + f.name()
                                                    + " · "
                                                    + f.grade().key(),
                                            f.id().id().toString());
                            ItemModels.apply(o.icon(), f.model());
                            out.add(o);
                        });
        return out;
    }

    /** 아무 물고기 · 등급 이상 · 등급 정확히. */
    List<Option> catchTargets() {
        List<Option> out = new ArrayList<>();
        out.add(option(Material.BUCKET, plain("quest.target.any"), "any"));
        for (Grade g : Grade.values()) {
            String name = plain("quest.grade." + g.key());
            out.add(option(Material.GOLD_NUGGET, name + "+", "grade:" + g.key() + "+"));
            out.add(option(Material.IRON_NUGGET, name, "grade:" + g.key()));
        }
        return out;
    }

    List<Option> items() {
        List<Option> out = new ArrayList<>();
        snapshot().all(ItemDef.TYPE).values().stream()
                .sorted(Comparator.comparing(d -> d.id().id().toString()))
                .forEach(
                        d -> {
                            Material m =
                                    Optional.ofNullable(Material.matchMaterial(d.baseItem()))
                                            .orElse(Material.PAPER);
                            Option o = option(m, d.name(), d.id().id().toString());
                            ItemModels.apply(o.icon(), d.model());
                            out.add(o);
                        });
        return out;
    }

    List<Option> npcs() {
        List<Option> out = new ArrayList<>();
        if (!snapshot().typeNames().contains(NpcDef.TYPE.name())) {
            return out;
        }
        snapshot().all(NpcDef.TYPE).values().stream()
                .sorted(Comparator.comparing(d -> d.id().id().toString()))
                .forEach(
                        d ->
                                out.add(
                                        option(
                                                Material.VILLAGER_SPAWN_EGG,
                                                d.name(),
                                                d.id().id().toString())));
        return out;
    }

    List<Option> regions(String prefix) {
        List<Option> out = new ArrayList<>();
        snapshot().all(RegionDef.TYPE).values().stream()
                .sorted(Comparator.comparing(d -> d.id().id().toString()))
                .forEach(d -> out.add(option(Material.FILLED_MAP, d.name(), prefix + d.id().id())));
        return out;
    }

    List<Option> quests(String except) {
        List<Option> out = new ArrayList<>();
        snapshot().all(QuestDef.TYPE).values().stream()
                .filter(d -> !d.id().id().toString().equals(except))
                .sorted(Comparator.comparing(d -> d.id().id().toString()))
                .forEach(d -> out.add(option(Material.BOOK, d.name(), d.id().id().toString())));
        return out;
    }

    List<Option> cutscenes() {
        List<Option> out = new ArrayList<>();
        if (!snapshot().typeNames().contains(CutsceneDef.TYPE.name())) {
            return out;
        }
        snapshot().all(CutsceneDef.TYPE).values().stream()
                .sorted(Comparator.comparing(d -> d.id().toString()))
                .forEach(
                        d -> out.add(option(Material.ENDER_EYE, d.id().name(), d.id().toString())));
        return out;
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(msg().get(key));
    }
}
