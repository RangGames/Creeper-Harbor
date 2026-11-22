package wiki.creeper.harbor.season.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.TitleService;
import wiki.creeper.harbor.season.content.TitleDef;

/**
 * Title screen (/칭호, docs 20 11.5). Owned titles are equipped by clicking; unowned titles only show
 * how to obtain them. The first slot unequips. The equipped value is the same as the title slot in
 * the Shop equipment screen.
 */
public final class TitleMenu {

    /** One slot. If def is null, it is the unequip button. */
    private record Row(@Nullable TitleDef def, boolean owned) {}

    private final HarborApi api;
    private final SeasonContentView content;
    private final TitleService titles;

    public TitleMenu(HarborApi api, SeasonContentView content, TitleService titles) {
        this.api = api;
        this.content = content;
        this.titles = titles;
    }

    public void open(Player viewer, int page) {
        MessageService msg = api.messages();
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(null, true));
        List<TitleDef> defs = new ArrayList<>(content.titles().values());
        defs.sort(
                Comparator.comparing((TitleDef d) -> !titles.has(viewer.getUniqueId(), d.id()))
                        .thenComparing(d -> d.id().toString()));
        for (TitleDef def : defs) {
            rows.add(new Row(def, titles.has(viewer.getUniqueId(), def.id())));
        }
        Optional<TitleId> equipped = titles.equipped(viewer.getUniqueId());
        long owned = rows.stream().filter(r -> r.def() != null && r.owned()).count();
        PagedMenu<Row> menu =
                new PagedMenu<>(
                        msg.get(
                                "season.title.menu.title",
                                Placeholder.unparsed("owned", String.valueOf(owned)),
                                Placeholder.unparsed("total", String.valueOf(defs.size()))),
                        6,
                        rows,
                        row -> entry(viewer, row, equipped),
                        Icons.icon(
                                Material.ARROW, msg.get("season.menu.previous"), List.of(), false),
                        Icons.icon(Material.ARROW, msg.get("season.menu.next"), List.of(), false));
        menu.show(viewer, page);
    }

    private PagedMenu.Entry entry(Player viewer, Row row, Optional<TitleId> equipped) {
        MessageService msg = api.messages();
        TitleDef def = row.def();
        if (def == null) {
            return new PagedMenu.Entry(
                    Icons.icon(
                            Material.BARRIER,
                            msg.get("season.title.menu.unequip"),
                            List.of(),
                            equipped.isEmpty()),
                    e -> {
                        titles.equip(viewer.getUniqueId(), null);
                        msg.send(viewer, "season.title.unequipped");
                        open(viewer, 0);
                    });
        }
        boolean isEquipped = equipped.isPresent() && equipped.get().equals(def.id());
        List<Component> lore = new ArrayList<>();
        if (!def.sourceHint().isEmpty()) {
            lore.add(
                    msg.get(
                            "season.title.menu.hint",
                            Placeholder.unparsed("hint", def.sourceHint())));
        }
        lore.add(msg.get("season.title.menu.style." + def.style()));
        if (!def.permanent()) {
            lore.add(msg.get("season.title.menu.conditional"));
        }
        lore.add(
                msg.get(
                        row.owned()
                                ? (isEquipped
                                        ? "season.title.menu.equipped"
                                        : "season.title.menu.click")
                                : "season.title.menu.locked"));
        Component name =
                row.owned()
                        ? titles.display(def.id())
                        : msg.get(
                                "season.title.menu.locked_name",
                                Placeholder.unparsed("id", def.id().shortName()));
        Material material = row.owned() ? Material.NAME_TAG : Material.GRAY_DYE;
        return new PagedMenu.Entry(
                Icons.icon(material, name, lore, isEquipped),
                e -> {
                    if (!row.owned()) {
                        return;
                    }
                    if (titles.equip(viewer.getUniqueId(), def.id())) {
                        msg.send(
                                viewer,
                                "season.title.equipped",
                                Placeholder.component("title", titles.display(def.id())));
                    }
                    open(viewer, 0);
                });
    }
}
