package wiki.creeper.harbor.season.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.season.api.League;
import wiki.creeper.harbor.season.api.RankEntry;
import wiki.creeper.harbor.season.api.RankingBoard;
import wiki.creeper.harbor.season.api.RankingService;
import wiki.creeper.harbor.season.content.RankingBoardDef;

/**
 * Ranking screen (/랭킹, docs 20 11.5). Ranks (head icons) in the top 5 rows; page, board and league
 * buttons plus your own rank in the last row. Reads only the cache, so there is no DB access.
 *
 * <pre>
 * [prev page][prev board][my rank][league1][league2][league3][computed at][next board][next page]
 * </pre>
 */
public final class RankingMenu {

    private static final int PER_PAGE = 45;

    private final HarborApi api;
    private final RankingService rankings;

    public RankingMenu(HarborApi api, RankingService rankings) {
        this.api = api;
        this.rankings = rankings;
    }

    public void open(Player viewer, String boardId, League league, int page) {
        MessageService msg = api.messages();
        List<RankingBoardDef> boards = rankings.boards();
        if (boards.isEmpty()) {
            msg.send(viewer, "season.ranking.no_boards");
            return;
        }
        int index = 0;
        for (int i = 0; i < boards.size(); i++) {
            if (boards.get(i).id().equals(boardId)) {
                index = i;
            }
        }
        RankingBoardDef def = boards.get(index);
        League shown = def.leagues().contains(league) ? league : def.defaultLeague();
        RankingBoard board = rankings.board(def.id(), shown);
        List<RankEntry> entries = board.entries();
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        int p = Math.clamp(page, 0, pages - 1);

        Menu menu =
                new Menu(
                        msg.get(
                                "season.ranking.title",
                                Placeholder.parsed("board", def.name()),
                                Placeholder.component("league", leagueName(shown))),
                        6);
        for (int i = 0; i < PER_PAGE && p * PER_PAGE + i < entries.size(); i++) {
            RankEntry e = entries.get(p * PER_PAGE + i);
            menu.button(i, entryIcon(def, e), null);
        }
        int nav = 45;
        int boardIndex = index;
        if (p > 0) {
            menu.button(
                    nav,
                    Icons.icon(Material.ARROW, msg.get("season.menu.previous"), List.of(), false),
                    ev -> open(viewer, def.id(), shown, p - 1));
        }
        if (p < pages - 1) {
            menu.button(
                    nav + 8,
                    Icons.icon(Material.ARROW, msg.get("season.menu.next"), List.of(), false),
                    ev -> open(viewer, def.id(), shown, p + 1));
        }
        if (boards.size() > 1) {
            RankingBoardDef prev = boards.get((boardIndex - 1 + boards.size()) % boards.size());
            RankingBoardDef next = boards.get((boardIndex + 1) % boards.size());
            menu.button(
                    nav + 1,
                    Icons.model(
                            Icons.icon(
                                    Material.SPYGLASS,
                                    msg.get(
                                            "season.ranking.prev_board",
                                            Placeholder.parsed("board", prev.name())),
                                    List.of(),
                                    false),
                            prev.icon()),
                    ev -> open(viewer, prev.id(), prev.defaultLeague(), 0));
            menu.button(
                    nav + 7,
                    Icons.model(
                            Icons.icon(
                                    Material.SPYGLASS,
                                    msg.get(
                                            "season.ranking.next_board",
                                            Placeholder.parsed("board", next.name())),
                                    List.of(),
                                    false),
                            next.icon()),
                    ev -> open(viewer, next.id(), next.defaultLeague(), 0));
        }
        Optional<RankEntry> mine = rankings.rankOf(viewer.getUniqueId(), def.id(), shown);
        menu.button(
                nav + 2,
                Icons.head(
                        viewer.getUniqueId(),
                        mine.map(
                                        e ->
                                                msg.get(
                                                        "season.ranking.mine",
                                                        Placeholder.unparsed(
                                                                "rank", String.valueOf(e.rank())),
                                                        Placeholder.unparsed(
                                                                "value",
                                                                Formats.rankValue(
                                                                        def.source(), e))))
                                .orElse(
                                        msg.get(
                                                "season.ranking.mine_none",
                                                Placeholder.unparsed(
                                                        "size", String.valueOf(def.size())))),
                        List.of()),
                null);
        int slot = nav + 3;
        for (League l : def.leagues()) {
            if (slot > nav + 5) {
                break;
            }
            League target = l;
            menu.button(
                    slot++,
                    Icons.icon(
                            l == League.ALL ? Material.NAUTILUS_SHELL : Material.PRISMARINE_SHARD,
                            leagueName(l),
                            List.of(),
                            l == shown),
                    ev -> open(viewer, def.id(), target, 0));
        }
        menu.button(
                nav + 6,
                Icons.icon(
                        Material.CLOCK,
                        msg.get(
                                "season.ranking.updated",
                                Placeholder.unparsed(
                                        "time",
                                        Formats.date(board.computedAt(), api.infra().zone()))),
                        List.of(
                                msg.get(
                                        "season.ranking.refresh_hint",
                                        Placeholder.unparsed(
                                                "minutes", String.valueOf(def.refreshMinutes())))),
                        false),
                null);
        menu.open(viewer);
    }

    private ItemStack entryIcon(RankingBoardDef def, RankEntry e) {
        MessageService msg = api.messages();
        List<Component> lore = new ArrayList<>();
        lore.add(
                msg.get(
                        "season.ranking.value",
                        Placeholder.unparsed("value", Formats.rankValue(def.source(), e))));
        if (e.members() > 1) {
            lore.add(
                    msg.get(
                            "season.ranking.party",
                            Placeholder.unparsed("count", String.valueOf(e.members() - 1))));
        }
        if (!e.achievedAt().equals(Instant.EPOCH)) {
            lore.add(
                    msg.get(
                            "season.ranking.date",
                            Placeholder.unparsed(
                                    "date", Formats.date(e.achievedAt(), api.infra().zone()))));
        }
        return Icons.head(
                e.uuid(),
                msg.get(
                        "season.ranking.entry",
                        Placeholder.unparsed("rank", String.valueOf(e.rank())),
                        Placeholder.unparsed("player", e.name())),
                lore);
    }

    Component leagueName(League league) {
        return api.messages().get("season.league." + league.key());
    }
}
