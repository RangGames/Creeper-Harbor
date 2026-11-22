package wiki.creeper.harbor.season.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.api.HallOfFameEntry;
import wiki.creeper.harbor.season.content.HallOfFameCategory;
import wiki.creeper.harbor.season.hof.HallOfFameManager;
import wiki.creeper.harbor.season.hof.HofMath;

/**
 * Hall of fame screen (/명예의전당, docs 20 11.4 display). Chest GUI (season and category tabs);
 * clicking an entry opens a Dialog with details (record, date, name at the time).
 *
 * <pre>
 * Top 5 rows: records (by rank per subject)
 * Last row: [previous season][category tabs x7][next season]
 * </pre>
 */
public final class HallOfFameMenu {

    private static final int PER_PAGE = 45;

    private final HarborApi api;
    private final HallOfFameManager hof;
    private final Tasks tasks;

    public HallOfFameMenu(HarborApi api, HallOfFameManager hof, Tasks tasks) {
        this.api = api;
        this.hof = hof;
        this.tasks = tasks;
    }

    /** If season is 0, the most recent season that has records. */
    public void open(Player viewer, int season, int categoryIndex) {
        List<HallOfFameCategory> categories = hof.categories();
        if (categories.isEmpty()) {
            api.messages().send(viewer, "season.hof.no_categories");
            return;
        }
        HallOfFameCategory cat =
                categories.get(Math.clamp(categoryIndex, 0, categories.size() - 1));
        var _ =
                hof.seasons()
                        .thenCompose(
                                seasons -> {
                                    int s = season > 0 ? season : seasons.getFirst();
                                    return hof.entries(s, cat.category())
                                            .thenAcceptAsync(
                                                    entries ->
                                                            render(
                                                                    viewer,
                                                                    seasons,
                                                                    s,
                                                                    categories,
                                                                    cat,
                                                                    entries),
                                                    tasks.main());
                                });
    }

    private void render(
            Player viewer,
            List<Integer> seasons,
            int season,
            List<HallOfFameCategory> categories,
            HallOfFameCategory cat,
            List<HallOfFameEntry> entries) {
        if (!viewer.isOnline()) {
            return;
        }
        MessageService msg = api.messages();
        Menu menu =
                new Menu(
                        msg.get(
                                "season.hof.title",
                                Placeholder.unparsed("season", String.valueOf(season)),
                                Placeholder.parsed("category", cat.name())),
                        6);
        for (int i = 0; i < Math.min(PER_PAGE, entries.size()); i++) {
            HallOfFameEntry e = entries.get(i);
            List<Component> lore = new ArrayList<>();
            lore.add(
                    msg.get(
                            "season.hof.value",
                            Placeholder.unparsed("value", HofMath.format(e.value(), cat.unit()))));
            lore.add(
                    msg.get(
                            "season.hof.date",
                            Placeholder.unparsed(
                                    "date", Formats.date(e.recordedAt(), api.infra().zone()))));
            lore.add(msg.get(e.finalized() ? "season.hof.final" : "season.hof.provisional"));
            String subject = hof.subjectName(e.subject());
            Component name =
                    msg.get(
                            subject.isEmpty() ? "season.hof.entry_plain" : "season.hof.entry",
                            Placeholder.unparsed("rank", String.valueOf(e.rank())),
                            Placeholder.unparsed("subject", subject),
                            Placeholder.unparsed("player", e.nameAtTime()));
            menu.button(
                    i, Icons.head(e.player(), name, lore), ev -> detail(viewer, cat, e, subject));
        }
        if (entries.isEmpty()) {
            menu.button(
                    22,
                    Icons.icon(Material.BARRIER, msg.get("season.hof.empty"), List.of(), false),
                    null);
        }
        int index = seasons.indexOf(season);
        if (index >= 0 && index + 1 < seasons.size()) {
            int older = seasons.get(index + 1);
            menu.button(
                    45,
                    Icons.icon(
                            Material.ARROW,
                            msg.get(
                                    "season.hof.prev_season",
                                    Placeholder.unparsed("season", String.valueOf(older))),
                            List.of(),
                            false),
                    ev -> open(viewer, older, categories.indexOf(cat)));
        }
        if (index > 0) {
            int newer = seasons.get(index - 1);
            menu.button(
                    53,
                    Icons.icon(
                            Material.ARROW,
                            msg.get(
                                    "season.hof.next_season",
                                    Placeholder.unparsed("season", String.valueOf(newer))),
                            List.of(),
                            false),
                    ev -> open(viewer, newer, categories.indexOf(cat)));
        }
        for (int i = 0; i < Math.min(7, categories.size()); i++) {
            HallOfFameCategory c = categories.get(i);
            int target = i;
            menu.button(
                    46 + i,
                    Icons.icon(
                            Material.GOLDEN_HELMET,
                            MiniMessage.miniMessage().deserialize(c.name()),
                            List.of(),
                            c.equals(cat)),
                    ev -> open(viewer, season, target));
        }
        menu.open(viewer);
    }

    private void detail(Player viewer, HallOfFameCategory cat, HallOfFameEntry e, String subject) {
        MessageService msg = api.messages();
        List<Component> body = new ArrayList<>();
        body.add(
                msg.get(
                        "season.hof.detail.record",
                        Placeholder.unparsed("value", HofMath.format(e.value(), cat.unit()))));
        body.add(
                msg.get(
                        "season.hof.detail.player",
                        Placeholder.unparsed("player", e.nameAtTime())));
        body.add(
                msg.get(
                        "season.hof.detail.date",
                        Placeholder.unparsed(
                                "date", Formats.date(e.recordedAt(), api.infra().zone()))));
        if (!subject.isEmpty()) {
            body.add(
                    msg.get("season.hof.detail.subject", Placeholder.unparsed("subject", subject)));
        }
        for (Map.Entry<String, String> d : e.detail().entrySet()) {
            body.add(
                    msg.get(
                            "season.hof.detail.extra",
                            Placeholder.unparsed("key", d.getKey()),
                            Placeholder.unparsed("value", d.getValue())));
        }
        api.dialogs()
                .notice(
                        viewer,
                        msg.get(
                                "season.hof.detail.title",
                                Placeholder.parsed("category", cat.name()),
                                Placeholder.unparsed("rank", String.valueOf(e.rank()))),
                        body);
    }
}
