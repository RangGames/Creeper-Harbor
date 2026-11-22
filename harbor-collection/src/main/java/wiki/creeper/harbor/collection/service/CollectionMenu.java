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
import wiki.creeper.harbor.collection.api.CollectionEntry;
import wiki.creeper.harbor.collection.api.CollectionScope;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.collection.view.CollectionView;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;

/**
 * Collection screen (/도감, docs 20 4.4 + proposed). The top row has all/region tabs (completion per
 * region) and the reward track, the middle 4 rows species, the bottom row page·filter (all ·
 * unregistered · registered)·grade·sort (number · grade · recent registration)·recent registrations
 * shortcut. Registered species show the species model icon + my record; unregistered show a
 * silhouette + hint. Clicking opens a Dialog detail (description, hint or my record, server #1 per
 * league). Region page backgrounds (custom font) will be added once the resource pack is ready.
 */
final class CollectionMenu {

    private static final int PER_PAGE = 36;
    private static final int FIRST = 9;
    private static final int NAV = 45;
    private static final int MAX_REGION_TABS = 7;
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private final HarborApi api;
    private final CollectionManager collection;

    CollectionMenu(HarborApi api, CollectionManager collection) {
        this.api = api;
        this.collection = collection;
    }

    private MessageService msg() {
        return api.messages();
    }

    void open(Player viewer, UUID owner, int page) {
        open(viewer, owner, CollectionView.State.DEFAULT.withPage(page));
    }

    void open(Player viewer, UUID owner, CollectionView.State state) {
        List<FishDef> defs = new ArrayList<>(api.content().snapshot().all(FishDef.TYPE).values());
        List<CollectionView.Item> items = new ArrayList<>();
        for (FishDef f : defs) {
            Optional<CollectionEntry> entry =
                    collection.entry(owner, f.id(), CollectionScope.TOTAL);
            items.add(
                    new CollectionView.Item(
                            f.id(),
                            f.no(),
                            f.grade(),
                            f.region(),
                            entry.map(CollectionEntry::firstCaught).orElse(null)));
        }
        List<CollectionView.Item> shown = CollectionView.apply(items, state);
        int pages = CollectionView.pages(shown.size(), PER_PAGE);
        int page = Math.clamp(state.page(), 0, pages - 1);
        CollectionView.State s = state.withPage(page);
        String season = pct(collection.percent(owner, CollectionScope.SEASON, null));
        Menu menu =
                new Menu(
                        msg().get(
                                        state.sort() == CollectionView.Sort.RECENT
                                                        && state.filter()
                                                                == CollectionView.Filter.REGISTERED
                                                ? "collection.menu.title_recent"
                                                : "collection.menu.title",
                                        Placeholder.unparsed("percent", season)),
                        6);
        tabs(menu, viewer, owner, s);
        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < shown.size(); i++) {
            CollectionView.Item item = shown.get(start + i);
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

    // ------------------------------------------------------------------ top row

    private void tabs(Menu menu, Player viewer, UUID owner, CollectionView.State s) {
        String total = pct(collection.percent(owner, CollectionScope.TOTAL, null));
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
            double percent = collection.percent(owner, CollectionScope.TOTAL, region.id());
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
                                            api,
                                            CollectionView.filled(percent / 100, width),
                                            width),
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
                        new MilestoneMenu(api, collection)
                                .open(viewer, owner, () -> open(viewer, owner, s)));
    }

    /** Region tab order: unlock level → ID. */
    private List<RegionDef> regions() {
        List<RegionDef> regions =
                new ArrayList<>(api.content().snapshot().all(RegionDef.TYPE).values());
        regions.sort(
                Comparator.comparingInt((RegionDef r) -> r.unlock().level())
                        .thenComparing(r -> r.id().id().toString()));
        return regions;
    }

    // ------------------------------------------------------------------ bottom row

    private void nav(
            Menu menu, Player viewer, UUID owner, CollectionView.State s, int pages, int count) {
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
                e -> open(viewer, owner, s.withGrade(CollectionView.nextGrade(s.grade()))));
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
                e -> open(viewer, owner, CollectionView.State.RECENT));
        menu.button(
                NAV + 6,
                MenuItems.icon(
                        Material.BARRIER,
                        msg().get("collection.menu.reset"),
                        List.of(msg().get("collection.menu.reset_hint")),
                        false),
                e -> open(viewer, owner, CollectionView.State.DEFAULT));
        if (s.page() < pages - 1) {
            menu.button(
                    NAV + 8,
                    MenuItems.icon(Material.ARROW, msg().get("collection.menu.next")),
                    e -> open(viewer, owner, s.withPage(s.page() + 1)));
        }
    }

    // ------------------------------------------------------------------ species

    private ItemStack icon(UUID owner, FishDef fish) {
        Optional<CollectionEntry> entry = collection.entry(owner, fish.id(), CollectionScope.TOTAL);
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

    private Component best(CollectionEntry entry) {
        return msg().get(
                        "collection.menu.best",
                        Placeholder.unparsed("size", CollectionManager.fmt(entry.maxSize())),
                        Placeholder.unparsed(
                                "quality",
                                Quality.of(Math.clamp(entry.bestQuality(), 1, 5)).display()),
                        Placeholder.unparsed("count", String.valueOf(entry.count())));
    }

    private String date(CollectionEntry entry) {
        ZoneId zone = api.time().now().getZone();
        return DATE.format(entry.firstCaught().atZone(zone));
    }

    /** Hint reveal per stage (0 region → 1 time of day → 2 weather·bait → 3 zone·moon·job). */
    private List<Component> hints(UUID owner, FishDef fish) {
        int level = collection.level(owner, fish.id());
        List<Component> lines = new ArrayList<>();
        lines.add(
                msg().get(
                                "collection.hint.region",
                                Placeholder.unparsed(
                                        "region", collection.regionName(fish.region()))));
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
        Optional<CollectionEntry> entry = collection.entry(owner, fish.id(), CollectionScope.TOTAL);
        if (entry.isPresent()) {
            fish.lore().forEach(line -> body.add(MiniMessage.miniMessage().deserialize(line)));
            body.add(best(entry.get()));
            for (String league : List.of("tier1", "tier2", "all")) {
                List<RecordEntry> top = collection.serverTop(fish.id(), league, 1);
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
