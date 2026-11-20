package wiki.creeper.harbor.collection.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.collection.api.CodexScope;
import wiki.creeper.harbor.collection.content.CodexRules;
import wiki.creeper.harbor.collection.content.CodexTitles;
import wiki.creeper.harbor.collection.content.StatBonus;
import wiki.creeper.harbor.collection.view.CodexView;
import wiki.creeper.harbor.collection.view.MilestoneTrack;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;

/**
 * 도감 보상 트랙 (추가 제안, /도감 → 별). 1줄 누적 도감 칭호(total_milestones), 2줄 시즌 도감 버프(season_buffs), 3줄 지역 완성(시즌
 * 도감, 칭호 + 물비늘). 받은 단계는 반짝인다. 지급은 지금처럼 자동이고 이 화면은 보여주기만 한다.
 */
final class MilestoneMenu {

    private final HarborApi api;
    private final CollectionManager codex;

    MilestoneMenu(HarborApi api, CollectionManager codex) {
        this.api = api;
        this.codex = codex;
    }

    private MessageService msg() {
        return api.messages();
    }

    void open(Player viewer, UUID owner, Runnable back) {
        CodexRules rules = codex.rules();
        double total = codex.percent(owner, CodexScope.TOTAL, null);
        double season = codex.percent(owner, CodexScope.SEASON, null);
        List<MilestoneTrack.Step> steps = MilestoneTrack.steps(rules, total, season);
        int width = MenuItems.barWidth(api);
        Menu menu = new Menu(msg().get("collection.track.title"), 4);
        // 1줄: 누적 칭호
        menu.button(
                0,
                MenuItems.icon(
                        Material.BOOK,
                        msg().get(
                                        "collection.track.total",
                                        Placeholder.unparsed("percent", CodexMenu.pct(total))),
                        List.of(
                                MenuItems.bar(api, CodexView.filled(total / 100, width), width),
                                next(steps, MilestoneTrack.Kind.TOTAL_TITLE)),
                        false),
                null);
        // 2줄: 시즌 버프
        menu.button(
                9,
                MenuItems.icon(
                        Material.CLOCK,
                        msg().get(
                                        "collection.track.season",
                                        Placeholder.unparsed("percent", CodexMenu.pct(season))),
                        List.of(
                                MenuItems.bar(api, CodexView.filled(season / 100, width), width),
                                next(steps, MilestoneTrack.Kind.SEASON_BUFF)),
                        false),
                null);
        int totalSlot = 1;
        int seasonSlot = 10;
        for (MilestoneTrack.Step step : steps) {
            if (step.kind() == MilestoneTrack.Kind.TOTAL_TITLE && totalSlot < 9) {
                menu.button(totalSlot++, titleStep(owner, step), null);
            } else if (step.kind() == MilestoneTrack.Kind.SEASON_BUFF && seasonSlot < 18) {
                menu.button(seasonSlot++, buffStep(step), null);
            }
        }
        for (int i = totalSlot; i < 9; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        for (int i = seasonSlot; i < 18; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        // 3줄: 지역 완성
        menu.button(
                18,
                MenuItems.icon(
                        Material.FILLED_MAP,
                        msg().get("collection.track.regions"),
                        List.of(
                                msg().get(
                                                "collection.track.regions_reward",
                                                Placeholder.unparsed(
                                                        "mulbineul",
                                                        String.valueOf(
                                                                rules.regionCompleteMulbineul())))),
                        false),
                null);
        List<RegionDef> regions =
                new ArrayList<>(api.content().snapshot().all(RegionDef.TYPE).values());
        regions.sort(
                Comparator.comparingInt((RegionDef r) -> r.unlock().level())
                        .thenComparing(r -> r.id().id().toString()));
        int slot = 19;
        for (RegionDef region : regions) {
            if (slot >= 27) {
                break;
            }
            double p = codex.percent(owner, CodexScope.SEASON, region.id());
            boolean done = p >= 100;
            String title = CodexTitles.regionTitle(rules.regionTitlePrefix(), region.id());
            menu.button(
                    slot++,
                    MenuItems.icon(
                            done ? Material.LIME_DYE : Material.GRAY_DYE,
                            msg().get(
                                            done
                                                    ? "collection.track.region_done"
                                                    : "collection.track.region",
                                            Placeholder.unparsed("name", region.name()),
                                            Placeholder.unparsed("percent", CodexMenu.pct(p))),
                            List.of(
                                    MenuItems.bar(api, CodexView.filled(p / 100, width), width),
                                    msg().get(
                                                    "collection.track.title_line",
                                                    Placeholder.component(
                                                            "title",
                                                            codex.seasonLink().titleName(title)))),
                            done),
                    null);
        }
        for (int i = slot; i < 27; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        for (int i = 27; i < 36; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        menu.button(
                27,
                MenuItems.icon(Material.ARROW, msg().get("collection.menu.back")),
                e -> back.run());
        menu.open(viewer);
    }

    private Component next(List<MilestoneTrack.Step> steps, MilestoneTrack.Kind kind) {
        var next = MilestoneTrack.next(steps, kind);
        return next.isPresent()
                ? msg().get(
                                "collection.track.next",
                                Placeholder.unparsed("percent", String.valueOf(next.getAsInt())))
                : msg().get("collection.track.all_done");
    }

    private org.bukkit.inventory.ItemStack titleStep(UUID owner, MilestoneTrack.Step step) {
        List<Component> lore = new ArrayList<>();
        if (!step.title().isEmpty()) {
            lore.add(
                    msg().get(
                                    "collection.track.title_line",
                                    Placeholder.component(
                                            "title", codex.seasonLink().titleName(step.title()))));
        }
        if (!step.title().isEmpty() && codex.seasonLink().owns(owner, step.title())) {
            lore.add(msg().get("collection.track.owned"));
        }
        lore.add(msg().get("collection.track.cosmetic_hint"));
        lore.add(
                msg().get(step.reached() ? "collection.track.reached" : "collection.track.locked"));
        return MenuItems.icon(
                step.reached() ? Material.NAME_TAG : Material.PAPER,
                msg().get(
                                "collection.track.step",
                                Placeholder.unparsed("percent", String.valueOf(step.percent()))),
                lore,
                step.reached());
    }

    private org.bukkit.inventory.ItemStack buffStep(MilestoneTrack.Step step) {
        List<Component> lore = new ArrayList<>();
        for (StatBonus bonus : step.modifiers()) {
            lore.add(stat(bonus));
        }
        lore.add(msg().get(step.reached() ? "collection.track.active" : "collection.track.locked"));
        return MenuItems.icon(
                step.reached() ? Material.GLOWSTONE_DUST : Material.GUNPOWDER,
                msg().get(
                                "collection.track.step",
                                Placeholder.unparsed("percent", String.valueOf(step.percent()))),
                lore,
                step.reached());
    }

    /** 스탯 이름은 collection.stat.<키> (없으면 키 그대로), 값은 비율이면 %로. */
    Component stat(StatBonus bonus) {
        String key = bonus.stat().key();
        Component name =
                msg().has("collection.stat." + key)
                        ? msg().get("collection.stat." + key)
                        : Component.text(key);
        double v = bonus.value();
        String value =
                Math.abs(v) < 1
                        ? String.format(Locale.ROOT, "%+.0f%%", v * 100)
                        : String.format(Locale.ROOT, "%+.1f", v);
        return msg().get(
                        "collection.track.buff_line",
                        Placeholder.component("stat", name),
                        Placeholder.unparsed("value", value));
    }
}
