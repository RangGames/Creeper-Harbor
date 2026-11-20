package wiki.creeper.harbor.collection.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.collection.api.CodexEntry;
import wiki.creeper.harbor.collection.api.CodexScope;
import wiki.creeper.harbor.collection.api.RecordEntry;
import wiki.creeper.harbor.collection.api.RecordService;
import wiki.creeper.harbor.collection.view.CodexView;
import wiki.creeper.harbor.collection.view.RecordRanks;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;

/**
 * 최대어 기록 화면 (/기록, 추가 제안). 인자 없이 열면 어종 고르기(누적 도감에 등록한 어종만 누를 수 있다), 어종을 고르면 리그 탭(1차 · 2차 · 전체)과 이번
 * 시즌 서버 TOP 10(플레이어 머리), 내 순위·시즌 최고·TOP 10까지 남은 크기. 데이터는 전광판과 같은 서버 기록 캐시라 DB를 읽지 않는다.
 */
final class RecordsMenu {

    private static final int PER_PAGE = 45;
    private static final int TOP = 10;

    private final HarborApi api;
    private final CollectionManager codex;

    RecordsMenu(HarborApi api, CollectionManager codex) {
        this.api = api;
        this.codex = codex;
    }

    private MessageService msg() {
        return api.messages();
    }

    // ------------------------------------------------------------------ 어종 고르기

    void pick(Player viewer, int page) {
        List<FishDef> fish = new ArrayList<>(api.content().snapshot().all(FishDef.TYPE).values());
        fish.sort(Comparator.comparingInt(FishDef::no));
        UUID uuid = viewer.getUniqueId();
        int pages = CodexView.pages(fish.size(), PER_PAGE);
        int shown = Math.clamp(page, 0, pages - 1);
        Menu menu = new Menu(msg().get("collection.records.pick_title"), 6);
        int start = shown * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < fish.size(); i++) {
            FishDef f = fish.get(start + i);
            boolean known = codex.entry(uuid, f.id(), CodexScope.TOTAL).isPresent();
            List<RecordEntry> top = codex.serverTop(f.id(), "all", 1);
            List<Component> lore = new ArrayList<>();
            if (known && !top.isEmpty()) {
                lore.add(
                        msg().get(
                                        "collection.records.leader",
                                        Placeholder.unparsed("player", top.getFirst().name()),
                                        Placeholder.unparsed(
                                                "size",
                                                CollectionManager.fmt(top.getFirst().size()))));
            }
            lore.add(
                    msg().get(
                                    known
                                            ? "collection.records.pick_hint"
                                            : "collection.records.pick_locked"));
            menu.button(
                    i,
                    MenuItems.fish(
                            api,
                            f,
                            known,
                            known
                                    ? msg().get(
                                                    "item.name." + f.grade().key(),
                                                    Placeholder.unparsed("name", f.name()))
                                    : msg().get(
                                                    "collection.menu.unknown",
                                                    Placeholder.unparsed(
                                                            "no", String.valueOf(f.no()))),
                            lore,
                            false),
                    known ? e -> open(viewer, f, "all", () -> pick(viewer, shown)) : null);
        }
        for (int i = 45; i < 54; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        if (shown > 0) {
            menu.button(
                    45,
                    MenuItems.icon(Material.ARROW, msg().get("collection.menu.previous")),
                    e -> pick(viewer, shown - 1));
        }
        if (shown < pages - 1) {
            menu.button(
                    53,
                    MenuItems.icon(Material.ARROW, msg().get("collection.menu.next")),
                    e -> pick(viewer, shown + 1));
        }
        menu.open(viewer);
    }

    // ------------------------------------------------------------------ 어종 하나

    void open(Player viewer, FishDef fish, String league, Runnable back) {
        Menu menu =
                new Menu(
                        msg().get(
                                        "collection.records.title",
                                        Placeholder.unparsed("name", fish.name())),
                        4);
        int slot = 0;
        for (String l : RecordService.LEAGUES) {
            boolean selected = l.equals(league);
            menu.button(
                    slot++,
                    MenuItems.icon(
                            selected ? Material.LIME_BANNER : Material.WHITE_BANNER,
                            msg().get(
                                            selected
                                                    ? "collection.records.league_selected"
                                                    : "collection.records.league",
                                            Placeholder.component(
                                                    "name", msg().get("collection.league." + l))),
                            List.of(),
                            selected),
                    selected ? null : e -> open(viewer, fish, l, back));
        }
        for (int i = slot; i < 9; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        List<RecordEntry> top = codex.serverTop(fish.id(), league, TOP);
        for (int i = 0; i < TOP; i++) {
            int at = 9 + i;
            if (i < top.size()) {
                menu.button(at, head(top.get(i), i + 1), null);
            } else {
                menu.button(
                        at,
                        MenuItems.icon(
                                Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                                msg().get(
                                                "collection.records.empty_rank",
                                                Placeholder.unparsed(
                                                        "rank", String.valueOf(i + 1)))),
                        null);
            }
        }
        for (int i = 19; i < 27; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        menu.button(22, mine(viewer, fish, top), null);
        for (int i = 27; i < 36; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        menu.button(
                27,
                MenuItems.icon(Material.ARROW, msg().get("collection.menu.back")),
                e -> back.run());
        menu.open(viewer);
    }

    private ItemStack head(RecordEntry entry, int rank) {
        String key =
                switch (rank) {
                    case 1 -> "collection.records.rank_1";
                    case 2 -> "collection.records.rank_2";
                    case 3 -> "collection.records.rank_3";
                    default -> "collection.records.rank";
                };
        ItemStack stack =
                MenuItems.icon(
                        Material.PLAYER_HEAD,
                        msg().get(
                                        key,
                                        Placeholder.unparsed("rank", String.valueOf(rank)),
                                        Placeholder.unparsed("player", entry.name())),
                        List.of(
                                msg().get(
                                                "collection.records.size",
                                                Placeholder.unparsed(
                                                        "size",
                                                        CollectionManager.fmt(entry.size())))),
                        rank == 1);
        stack.setData(
                DataComponentTypes.PROFILE,
                ResolvableProfile.resolvableProfile().uuid(entry.player()).build());
        return stack;
    }

    /** 내 순위 · 이번 시즌 최고 · TOP 10까지 남은 크기. */
    private ItemStack mine(Player viewer, FishDef fish, List<RecordEntry> top) {
        UUID uuid = viewer.getUniqueId();
        Optional<CodexEntry> season = codex.entry(uuid, fish.id(), CodexScope.SEASON);
        Optional<CodexEntry> total = codex.entry(uuid, fish.id(), CodexScope.TOTAL);
        List<Component> lore = new ArrayList<>();
        OptionalInt rank = RecordRanks.rank(top, uuid);
        if (rank.isPresent()) {
            lore.add(
                    msg().get(
                                    "collection.records.my_rank",
                                    Placeholder.unparsed("rank", String.valueOf(rank.getAsInt()))));
        } else if (season.isPresent()) {
            OptionalDouble gap = RecordRanks.gapToTop(top, season.get().maxSize(), TOP);
            lore.add(
                    msg().get(
                                    "collection.records.my_out",
                                    Placeholder.unparsed(
                                            "gap", CollectionManager.fmt(gap.orElse(0)))));
        } else {
            lore.add(msg().get("collection.records.my_none"));
        }
        season.ifPresent(
                e ->
                        lore.add(
                                msg().get(
                                                "collection.records.my_season",
                                                Placeholder.unparsed(
                                                        "size",
                                                        CollectionManager.fmt(e.maxSize())))));
        total.ifPresent(
                e ->
                        lore.add(
                                msg().get(
                                                "collection.records.my_total",
                                                Placeholder.unparsed(
                                                        "size",
                                                        CollectionManager.fmt(e.maxSize())))));
        lore.add(msg().get("collection.records.league_hint"));
        return MenuItems.icon(
                Material.FISHING_ROD, msg().get("collection.records.mine"), lore, rank.isPresent());
    }
}
