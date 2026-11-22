package wiki.creeper.harbor.collection.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.collection.api.CollectionScope;
import wiki.creeper.harbor.collection.content.AquariumRules;
import wiki.creeper.harbor.collection.content.StatBonus;
import wiki.creeper.harbor.collection.data.CollectionData;
import wiki.creeper.harbor.collection.view.AquariumView;
import wiki.creeper.harbor.collection.view.CollectionView;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;

/**
 * Harbor aquarium progress screen (/수족관, proposed). The top row has the current stage, donation
 * rate bar, species remaining until the next stage, per-stage thresholds and buffs, a donation box
 * shortcut, and species filters (remaining · donated · all). The middle 4 rows are donatable
 * species; species in the inventory come first and glint, and clicking opens the donation
 * confirmation screen (the same {@link AquariumManager#donateSlot} → donateItems transaction as
 * donating from hand). Species not yet donated and not in your collection are hidden as
 * silhouettes.
 */
final class AquariumMenu {

    private static final int PER_PAGE = 36;

    private final HarborApi api;
    private final AquariumManager aquarium;
    private final CollectionManager collection;

    AquariumMenu(HarborApi api, AquariumManager aquarium, CollectionManager collection) {
        this.api = api;
        this.aquarium = aquarium;
        this.collection = collection;
    }

    private MessageService msg() {
        return api.messages();
    }

    void open(Player viewer, AquariumView.Filter filter, int page) {
        AquariumService.Status status = aquarium.status();
        AquariumRules rules = aquarium.rules();
        List<AquariumView.StageRow> stages =
                AquariumView.stages(rules, status.stage(), status.donatable());
        int season = api.infra().season().value();
        Map<FishId, Integer> donatable = new LinkedHashMap<>();
        Map<FishId, FishDef> defs = new HashMap<>();
        for (FishDef f : api.content().snapshot().all(FishDef.TYPE).values()) {
            if (AquariumManager.donatableFish(f, season)) {
                donatable.put(f.id(), f.no());
                defs.put(f.id(), f);
            }
        }
        List<AquariumView.SpeciesRow> rows =
                AquariumView.species(
                        donatable, aquarium.donatedSpecies(), inventory(viewer), filter);
        int pages = CollectionView.pages(rows.size(), PER_PAGE);
        int shown = Math.clamp(page, 0, pages - 1);
        Menu menu = new Menu(msg().get("collection.aquarium.menu.title"), 6);
        header(menu, viewer, status, stages, filter);
        CollectionData mine = collection.data(viewer.getUniqueId());
        int start = shown * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < rows.size(); i++) {
            AquariumView.SpeciesRow row = rows.get(start + i);
            FishDef def = defs.get(row.fish());
            if (def == null) {
                continue;
            }
            menu.button(
                    9 + i,
                    species(viewer.getUniqueId(), def, row, mine, rules),
                    row.inInventory()
                            ? e -> donate(viewer, row.inventorySlot(), filter, shown)
                            : null);
        }
        if (rows.isEmpty()) {
            menu.button(
                    22,
                    MenuItems.icon(
                            Material.PAPER,
                            msg().get("collection.aquarium.menu.empty_" + filter.key())),
                    null);
        }
        for (int i = 45; i < 54; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        if (shown > 0) {
            menu.button(
                    45,
                    MenuItems.icon(Material.ARROW, msg().get("collection.menu.previous")),
                    e -> open(viewer, filter, shown - 1));
        }
        menu.button(
                49,
                MenuItems.icon(
                        Material.MAP,
                        msg().get(
                                        "collection.menu.page",
                                        Placeholder.unparsed("page", String.valueOf(shown + 1)),
                                        Placeholder.unparsed("pages", String.valueOf(pages)),
                                        Placeholder.unparsed(
                                                "count", String.valueOf(rows.size())))),
                null);
        if (shown < pages - 1) {
            menu.button(
                    53,
                    MenuItems.icon(Material.ARROW, msg().get("collection.menu.next")),
                    e -> open(viewer, filter, shown + 1));
        }
        menu.open(viewer);
    }

    private void header(
            Menu menu,
            Player viewer,
            AquariumService.Status status,
            List<AquariumView.StageRow> stages,
            AquariumView.Filter filter) {
        int width = MenuItems.barWidth(api);
        Optional<AquariumView.StageRow> next =
                stages.stream().filter(AquariumView.StageRow::next).findFirst();
        double target = next.map(r -> r.stage().rate()).orElse(1.0);
        List<Component> lore = new ArrayList<>();
        lore.add(
                MenuItems.bar(
                        api,
                        CollectionView.filled(target <= 0 ? 1 : status.rate() / target, width),
                        width));
        lore.add(
                msg().get(
                                "collection.aquarium.menu.rate",
                                Placeholder.unparsed(
                                        "rate",
                                        String.format(Locale.ROOT, "%.1f", status.rate() * 100)),
                                Placeholder.unparsed("species", String.valueOf(status.species())),
                                Placeholder.unparsed(
                                        "donatable", String.valueOf(status.donatable()))));
        AquariumView.remaining(stages, status.species())
                .ifPresentOrElse(
                        left ->
                                lore.add(
                                        msg().get(
                                                        "collection.aquarium.menu.remaining",
                                                        Placeholder.unparsed(
                                                                "stage",
                                                                String.valueOf(
                                                                        next.map(
                                                                                        r ->
                                                                                                r.stage()
                                                                                                        .stage())
                                                                                .orElse(0))),
                                                        Placeholder.unparsed(
                                                                "left", String.valueOf(left)))),
                        () -> lore.add(msg().get("collection.aquarium.menu.max_stage")));
        lore.add(
                msg().get(
                                "collection.aquarium.menu.points",
                                Placeholder.unparsed("points", String.valueOf(status.points()))));
        menu.button(
                0,
                MenuItems.icon(
                        Material.HEART_OF_THE_SEA,
                        msg().get(
                                        "collection.aquarium.menu.stage",
                                        Placeholder.unparsed(
                                                "stage", String.valueOf(status.stage()))),
                        lore,
                        true),
                null);
        int slot = 1;
        for (AquariumView.StageRow row : stages) {
            if (slot > 6) {
                break;
            }
            List<Component> stageLore = new ArrayList<>();
            stageLore.add(
                    msg().get(
                                    "collection.aquarium.menu.stage_need",
                                    Placeholder.unparsed(
                                            "rate",
                                            String.format(
                                                    Locale.ROOT, "%.0f", row.stage().rate() * 100)),
                                    Placeholder.unparsed("needed", String.valueOf(row.needed()))));
            for (StatBonus bonus : row.stage().modifiers()) {
                stageLore.add(new MilestoneMenu(api, collection).stat(bonus));
            }
            String key = "collection.aquarium.menu.stage_note." + row.stage().stage();
            if (msg().has(key)) {
                stageLore.add(msg().get(key));
            }
            stageLore.add(
                    msg().get(
                                    row.reached()
                                            ? "collection.aquarium.menu.reached"
                                            : row.next()
                                                    ? "collection.aquarium.menu.next"
                                                    : "collection.aquarium.menu.locked"));
            menu.button(
                    slot++,
                    MenuItems.icon(
                            row.reached()
                                    ? Material.LIME_STAINED_GLASS
                                    : row.next()
                                            ? Material.YELLOW_STAINED_GLASS
                                            : Material.GRAY_STAINED_GLASS,
                            msg().get(
                                            "collection.aquarium.menu.stage_name",
                                            Placeholder.unparsed(
                                                    "stage", String.valueOf(row.stage().stage()))),
                            stageLore,
                            row.reached()),
                    null);
        }
        for (int i = slot; i < 7; i++) {
            menu.button(i, MenuItems.filler(), null);
        }
        menu.button(
                7,
                MenuItems.icon(
                        Material.CHEST,
                        msg().get("collection.aquarium.menu.box"),
                        List.of(msg().get("collection.aquarium.menu.box_hint")),
                        false),
                e -> {
                    viewer.closeInventory();
                    aquarium.openDonationBox(viewer);
                });
        menu.button(
                8,
                MenuItems.icon(
                        Material.HOPPER,
                        msg().get(
                                        "collection.aquarium.menu.filter",
                                        Placeholder.component(
                                                "value",
                                                msg().get(
                                                                "collection.aquarium.menu.filter_"
                                                                        + filter.key()))),
                        List.of(msg().get("collection.menu.cycle_hint")),
                        false),
                e -> open(viewer, filter.next(), 0));
    }

    private ItemStack species(
            UUID viewer,
            FishDef fish,
            AquariumView.SpeciesRow row,
            CollectionData mine,
            AquariumRules rules) {
        boolean known =
                row.donated()
                        || collection.entry(viewer, fish.id(), CollectionScope.TOTAL).isPresent();
        List<Component> lore = new ArrayList<>();
        lore.add(
                msg().get(
                                row.donated()
                                        ? "collection.aquarium.menu.donated"
                                        : "collection.aquarium.menu.missing"));
        if (!known) {
            lore.add(
                    msg().get(
                                    "collection.hint.region",
                                    Placeholder.unparsed(
                                            "region", collection.regionName(fish.region()))));
        }
        lore.add(
                msg().get(
                                "collection.aquarium.menu.my_count",
                                Placeholder.unparsed(
                                        "count",
                                        String.valueOf(
                                                mine.donations().getOrDefault(fish.id(), 0))),
                                Placeholder.unparsed(
                                        "cap", String.valueOf(rules.perPlayerSpeciesCap()))));
        if (row.inInventory()) {
            lore.add(
                    msg().get(
                                    row.donated()
                                            ? "collection.aquarium.menu.click_donate"
                                            : "collection.aquarium.menu.click_donate_first"));
        }
        Component name =
                known
                        ? msg().get(
                                        "item.name." + fish.grade().key(),
                                        Placeholder.unparsed("name", fish.name()))
                        : msg().get(
                                        "collection.menu.unknown",
                                        Placeholder.unparsed("no", String.valueOf(fish.no())));
        return MenuItems.fish(api, fish, known, name, lore, row.inInventory());
    }

    /** Inventory (slots 0~35): species → first slot holding it. */
    private Map<FishId, Integer> inventory(Player player) {
        Map<FishId, Integer> out = new HashMap<>();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            Optional<HarborItem.Fish> fish = aquarium.fishOf(contents[slot]);
            if (fish.isPresent()) {
                out.putIfAbsent(fish.get().species(), slot);
            }
        }
        return out;
    }

    private void donate(Player viewer, int slot, AquariumView.Filter filter, int page) {
        viewer.closeInventory();
        var _ =
                aquarium.donateSlot(viewer, slot, "menu", "collection.aquarium.changed_slot")
                        .thenAccept(
                                ok -> {
                                    if (viewer.isOnline()) {
                                        open(viewer, filter, page);
                                    }
                                });
    }
}
