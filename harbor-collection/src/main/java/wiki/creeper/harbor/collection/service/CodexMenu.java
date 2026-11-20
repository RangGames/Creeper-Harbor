package wiki.creeper.harbor.collection.service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.collection.api.CodexEntry;
import wiki.creeper.harbor.collection.api.CodexScope;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.collection.view.CodexView;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;

/**
 * 도감 화면 (/도감, docs 20 4.4 + 추가 제안). 윗줄은 전체·지역 탭(지역별 달성률)과 보상 트랙, 가운데 4줄은 어종, 아랫줄은 페이지·필터(전체 · 미등록 ·
 * 등록)·등급·정렬(번호 · 등급 · 최근 등록)·최근 등록 바로 보기. 등록 어종은 어종 모델 아이콘 + 내 기록, 미등록은 실루엣 + 힌트. 누르면 Dialog 상세(설명,
 * 힌트 또는 내 기록, 리그별 서버 1위). 지역 페이지 배경(커스텀 폰트)은 리소스팩이 준비되면 붙인다.
 */
final class CodexMenu {

    private static final int PER_PAGE = 36;
    private static final int FIRST = 9;
    private static final int NAV = 45;
    private static final int MAX_REGION_TABS = 7;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private final HarborApi api;
    private final CollectionManager codex;

    CodexMenu(HarborApi api, CollectionManager codex) {
        this.api = api;
        this.codex = codex;
    }

    private MessageService msg() {
        return api.messages();
    }

    void open(Player viewer, UUID owner, int page) {
        open(viewer, owner, CodexView.State.DEFAULT.withPage(page));
    }

    void open(Player viewer, UUID owner, CodexView.State state) {
        List<FishDef> defs = new ArrayList<>(api.content().snapshot().all(FishDef.TYPE).values());
        List<CodexView.Item> items = new ArrayList<>();
        for (FishDef f : defs) {
            Optional<CodexEntry> entry = codex.entry(owner, f.id(), CodexScope.TOTAL);
            items.add(
                    new CodexView.Item(
                            f.id(),
                            f.no(),
                            f.grade(),
                            f.region(),
                            entry.map(CodexEntry::firstCaught).orElse(null)));
        }
        List<CodexView.Item> shown = CodexView.apply(items, state);
        int pages = CodexView.pages(shown.size(), PER_PAGE);
        int page = Math.clamp(state.page(), 0, pages - 1);
        CodexView.State s = state.withPage(page);
        String season = pct(codex.percent(owner, CodexScope.SEASON, null));
        Menu menu =
                new Menu(
                        msg().get(
                                        state.sort() == CodexView.Sort.RECENT
                                                        && state.filter()
                                                                == CodexView.Filter.REGISTERED
                                                ? "collection.menu.title_recent"
                                                : "collection.menu.title",
                                        Placeholder.unparsed("percent", season)),
                        6);
        tabs(menu, viewer, owner, s);
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < shown.size(); i++) {
            CodexView.Item item = shown.get(start + i);
            int slot = FIRST + i;
            api.content()
                    .snapshot()
                    .get(FishDef.TYPE, item.id().id())
                    .ifPresent(
                            f -> menu.button(slot, icon(owner, f), e -> detail(viewer, owner, f)));
        }
        if (shown.isEmpty()) {
            menu.button(
                    FIRST + 13,
                    MenuItems.icon(Material.PAPER, msg().get("collection.menu.empty")),
                    null);
        }
        nav(menu, viewer, owner, s, pages, shown.size());
        menu.open(viewer);
    }

    // ------------------------------------------------------------------ 윗줄

    private void tabs(Menu menu, Player viewer, UUID owner, CodexView.State s) {
        String total = pct(codex.percent(owner, CodexScope.TOTAL, null));
        menu.button(
                0,
                MenuItems.icon(
                        Material.BOOK,
                        msg().get(
                                        s.region() == null
                                                ? "collection.menu.tab_selected"
                                                : "collection.menu.tab",
                                        Placeholder.component(
                                                "name", msg().get("collection.menu.tab_all"))),
                        List.of(
                                msg().get(
                                                "collection.menu.tab_percent",
                                                Placeholder.unparsed("percent", total))),
                        s.region() == null),
                s.region() == null ? null : e -> open(viewer, owner, s.withRegion(null)));
        List<RegionDef> regions = regions();
        for (int i = 0; i < MAX_REGION_TABS; i++) {
            int slot = 1 + i;
            if (i >= regions.size()) {
                menu.button(slot, MenuItems.filler(), null);
                continue;
            }
            RegionDef region = regions.get(i);
            boolean selected = region.id().equals(s.region());
            double percent = codex.percent(owner, CodexScope.TOTAL, region.id());
            int width = MenuItems.barWidth(api);
            menu.button(
                    slot,
                    MenuItems.icon(
                            percent >= 100 ? Material.FILLED_MAP : Material.MAP,
                            msg().get(
                                            selected
                                                    ? "collection.menu.tab_selected"
                                                    : "collection.menu.tab",
                                            Placeholder.unparsed("name", region.name())),
                            List.of(
                                    MenuItems.bar(
                                            api, CodexView.filled(percent / 100, width), width),
                                    msg().get(
                                                    "collection.menu.tab_percent",
                                                    Placeholder.unparsed("percent", pct(percent)))),
                            selected || percent >= 100),
                    selected ? null : e -> open(viewer, owner, s.withRegion(region.id())));
        }
        menu.button(
                8,
                MenuItems.icon(
                        Material.NETHER_STAR,
                        msg().get("collection.menu.track"),
                        List.of(msg().get("collection.menu.track_hint")),
                        false),
                e ->
                        new MilestoneMenu(api, codex)
                                .open(viewer, owner, () -> open(viewer, owner, s)));
    }

    /** 지역 탭 순서: 해금 레벨 → ID. */
    private List<RegionDef> regions() {
        List<RegionDef> regions =
                new ArrayList<>(api.content().snapshot().all(RegionDef.TYPE).values());
        regions.sort(
                Comparator.comparingInt((RegionDef r) -> r.unlock().level())
                        .thenComparing(r -> r.id().id().toString()));
        return regions;
    }

    // ------------------------------------------------------------------ 아랫줄

    private void nav(
            Menu menu, Player viewer, UUID owner, CodexView.State s, int pages, int count) {
        for (int i = NAV; i < NAV + 9; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        if (s.page() > 0) {
            menu.button(
                    NAV,
                    MenuItems.icon(Material.ARROW, msg().get("collection.menu.previous")),
                    e -> open(viewer, owner, s.withPage(s.page() - 1)));
        }
        menu.button(
                NAV + 1,
                MenuItems.icon(
                        Material.HOPPER,
                        msg().get(
                                        "collection.menu.filter",
                                        Placeholder.component(
                                                "value",
                                                msg().get(
                                                                "collection.menu.filter_"
                                                                        + s.filter().key()))),
                        List.of(msg().get("collection.menu.cycle_hint")),
                        false),
                e -> open(viewer, owner, s.withFilter(s.filter().next())));
        Component grade =
                s.grade() == null
                        ? msg().get("collection.menu.grade_all")
                        : msg().get("collection.grade." + s.grade().key());
        menu.button(
                NAV + 2,
                MenuItems.icon(
                        Material.AMETHYST_SHARD,
                        msg().get("collection.menu.grade", Placeholder.component("value", grade)),
                        List.of(msg().get("collection.menu.cycle_hint")),
                        false),
                e -> open(viewer, owner, s.withGrade(CodexView.nextGrade(s.grade()))));
        menu.button(
                NAV + 3,
                MenuItems.icon(
                        Material.COMPARATOR,
                        msg().get(
                                        "collection.menu.sort",
                                        Placeholder.component(
                                                "value",
                                                msg().get(
                                                                "collection.menu.sort_"
                                                                        + s.sort().key()))),
                        List.of(msg().get("collection.menu.cycle_hint")),
                        false),
                e -> open(viewer, owner, s.withSort(s.sort().next())));
        menu.button(
                NAV + 4,
                MenuItems.icon(
                        Material.MAP,
                        msg().get(
                                        "collection.menu.page",
                                        Placeholder.unparsed("page", String.valueOf(s.page() + 1)),
                                        Placeholder.unparsed("pages", String.valueOf(pages)),
                                        Placeholder.unparsed("count", String.valueOf(count)))),
                null);
        menu.button(
                NAV + 5,
                MenuItems.icon(
                        Material.CLOCK,
                        msg().get("collection.menu.recent"),
                        List.of(msg().get("collection.menu.recent_hint")),
                        false),
                e -> open(viewer, owner, CodexView.State.RECENT));
        menu.button(
                NAV + 6,
                MenuItems.icon(
                        Material.BARRIER,
                        msg().get("collection.menu.reset"),
                        List.of(msg().get("collection.menu.reset_hint")),
                        false),
                e -> open(viewer, owner, CodexView.State.DEFAULT));
        if (s.page() < pages - 1) {
            menu.button(
                    NAV + 8,
                    MenuItems.icon(Material.ARROW, msg().get("collection.menu.next")),
                    e -> open(viewer, owner, s.withPage(s.page() + 1)));
        }
    }

    // ------------------------------------------------------------------ 어종

    private ItemStack icon(UUID owner, FishDef fish) {
        Optional<CodexEntry> entry = codex.entry(owner, fish.id(), CodexScope.TOTAL);
        List<Component> lore = new ArrayList<>();
        if (entry.isPresent()) {
            lore.add(
                    msg().get(
                                    "collection.menu.no",
                                    Placeholder.unparsed("no", String.valueOf(fish.no()))));
            lore.add(best(entry.get()));
            lore.add(
                    msg().get(
                                    "collection.menu.first_caught",
                                    Placeholder.unparsed("date", date(entry.get()))));
            lore.add(msg().get("collection.menu.detail_hint"));
            return MenuItems.fish(
                    api,
                    fish,
                    true,
                    msg().get(
                                    "item.name." + fish.grade().key(),
                                    Placeholder.unparsed("name", fish.name())),
                    lore,
                    false);
        }
        lore.addAll(hints(owner, fish));
        return MenuItems.fish(
                api,
                fish,
                false,
                msg().get(
                                "collection.menu.unknown",
                                Placeholder.unparsed("no", String.valueOf(fish.no()))),
                lore,
                false);
    }

    private Component best(CodexEntry entry) {
        return msg().get(
                        "collection.menu.best",
                        Placeholder.unparsed("size", CollectionManager.fmt(entry.maxSize())),
                        Placeholder.unparsed(
                                "quality",
                                Quality.of(Math.clamp(entry.bestQuality(), 1, 5)).display()),
                        Placeholder.unparsed("count", String.valueOf(entry.count())));
    }

    private String date(CodexEntry entry) {
        ZoneId zone = api.time().now().getZone();
        return DATE.format(entry.firstCaught().atZone(zone));
    }

    /** 힌트 단계별 공개 (0 지역 → 1 시간대 → 2 날씨·미끼 → 3 구역·달·직업). */
    private List<Component> hints(UUID owner, FishDef fish) {
        int level = codex.level(owner, fish.id());
        List<Component> lines = new ArrayList<>();
        lines.add(
                msg().get(
                                "collection.hint.region",
                                Placeholder.unparsed("region", codex.regionName(fish.region()))));
        if (level >= 1) {
            lines.add(
                    msg().get(
                                    "collection.hint.time",
                                    Placeholder.unparsed("value", list(fish.time()))));
        }
        if (level >= 2) {
            lines.add(
                    msg().get(
                                    "collection.hint.weather",
                                    Placeholder.unparsed("value", list(fish.weather()))));
            lines.add(
                    msg().get(
                                    "collection.hint.bait",
                                    Placeholder.unparsed(
                                            "value",
                                            list(
                                                    fish.bait().stream()
                                                            .map(b -> b.id().name())
                                                            .toList()))));
        }
        if (level >= 3) {
            lines.add(
                    msg().get(
                                    "collection.hint.extra",
                                    Placeholder.unparsed("area", list(fish.area())),
                                    Placeholder.unparsed("moon", list(fish.moon())),
                                    Placeholder.unparsed(
                                            "tier",
                                            String.valueOf(Math.max(1, fish.jobTierMin())))));
        }
        return lines;
    }

    private static String list(List<String> values) {
        return values.isEmpty() ? "-" : String.join(", ", values);
    }

    private void detail(Player viewer, UUID owner, FishDef fish) {
        List<Component> body = new ArrayList<>();
        Optional<CodexEntry> entry = codex.entry(owner, fish.id(), CodexScope.TOTAL);
        if (entry.isPresent()) {
            fish.lore().forEach(line -> body.add(MiniMessage.miniMessage().deserialize(line)));
            body.add(best(entry.get()));
            for (String league : List.of("tier1", "tier2", "all")) {
                List<RecordEntry> top = codex.serverTop(fish.id(), league, 1);
                if (!top.isEmpty()) {
                    body.add(
                            msg().get(
                                            "collection.menu.leader",
                                            Placeholder.unparsed("league", league),
                                            Placeholder.unparsed("player", top.getFirst().name()),
                                            Placeholder.unparsed(
                                                    "size",
                                                    CollectionManager.fmt(top.getFirst().size()))));
                }
            }
        } else {
            body.addAll(hints(owner, fish));
        }
        viewer.closeInventory();
        Component title =
                entry.isPresent()
                        ? msg().get(
                                        "item.name." + fish.grade().key(),
                                        Placeholder.unparsed("name", fish.name()))
                        : msg().get(
                                        "collection.menu.unknown",
                                        Placeholder.unparsed("no", String.valueOf(fish.no())));
        api.dialogs().notice(viewer, title, body);
    }

    static String pct(double percent) {
        return String.format(Locale.ROOT, "%.1f", percent);
    }
}
