package wiki.creeper.harbor.collection.display;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.api.CollectionEntry;
import wiki.creeper.harbor.collection.api.DisplaySlot;
import wiki.creeper.harbor.collection.content.DisplayCaseRules;
import wiki.creeper.harbor.collection.data.CollectionRepository;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.PlotView;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.SlotView;
import wiki.creeper.harbor.collection.service.CollectionManager;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.core.task.Tasks;

/** Visitor info display and the edit menus of a personal aquarium slot. */
final class DisplayCaseMenus {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DisplayCaseManager manager;
    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;
    private final ShopThemes themes;

    DisplayCaseMenus(
            DisplayCaseManager manager,
            Plugin plugin,
            HarborApi api,
            Tasks tasks,
            ShopThemes themes) {
        this.manager = manager;
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.themes = themes;
    }

    /**
     * Visitor info: name, stars, size, first catch date and the owner's server rank, shown for a
     * few seconds only to the player who clicked.
     */
    void showInfo(Player player, PlotView view, int slot) {
        DisplaySlot row = view.rows.get(slot);
        SlotView sv = view.views.get(slot);
        if (row == null || sv == null || !DisplayCaseRules.slotOpen(slot, view.slotCount)) {
            api.messages().send(player, "collection.display.empty");
            return;
        }
        SeasonId season = api.infra().season();
        UUID owner = view.plot.owner();
        var _ =
                api.database()
                        .async(c -> CollectionRepository.rank(c, row.fish(), season, "all", owner))
                        .exceptionally(e -> 0)
                        .thenAcceptAsync(rank -> info(player, view, sv, row, rank), tasks.main());
    }

    private void info(Player player, PlotView view, SlotView sv, DisplaySlot row, int rank) {
        if (!player.isOnline()) {
            return;
        }
        MessageService msg = api.messages();
        String name =
                api.content()
                        .snapshot()
                        .get(FishDef.TYPE, row.fish().id())
                        .map(FishDef::name)
                        .orElse(row.fish().id().name());
        List<Component> lines = new ArrayList<>();
        lines.add(
                msg.get(
                        "collection.display.info_name",
                        Placeholder.unparsed("name", name),
                        Placeholder.unparsed("owner", view.plot.ownerName())));
        lines.add(
                msg.get(
                        "collection.display.info_size",
                        Placeholder.unparsed("size", CollectionManager.fmt(row.size())),
                        Placeholder.unparsed(
                                "quality", Quality.of(Math.clamp(row.quality(), 1, 5)).display())));
        lines.add(
                msg.get(
                        "collection.display.info_date",
                        Placeholder.unparsed(
                                "date",
                                DATE.withZone(api.time().zone()).format(row.firstCaught()))));
        lines.add(
                rank > 0
                        ? msg.get(
                                "collection.display.info_rank",
                                Placeholder.unparsed("rank", String.valueOf(rank)))
                        : msg.get("collection.display.info_no_rank"));
        World world = Bukkit.getWorld(view.plot.world());
        if (world == null) {
            return;
        }
        Location at = sv.center.clone().add(0, 0.9, 0);
        Component text = Component.join(JoinConfiguration.newlines(), lines);
        TextDisplay display =
                world.spawn(
                        at,
                        TextDisplay.class,
                        e -> {
                            e.setPersistent(false);
                            e.setVisibleByDefault(false);
                            e.setBillboard(Display.Billboard.CENTER);
                            e.text(text);
                        });
        player.showEntity(plugin, display);
        var _ = tasks.later(manager.rules().infoSeconds() * 20L, display::remove);
    }

    // ------------------------------------------------------------------ edit menu

    void openEditor(Player player, PlotView view, int slot) {
        MessageService msg = api.messages();
        if (!DisplayCaseRules.slotOpen(slot, view.slotCount)) {
            api.messages()
                    .send(
                            player,
                            "collection.display.slot_locked",
                            Placeholder.unparsed("count", String.valueOf(view.slotCount)));
            return;
        }
        long plotId = view.plot.plotId();
        Menu menu =
                new Menu(
                        msg.get(
                                "collection.display.menu_title",
                                Placeholder.unparsed("slot", String.valueOf(slot + 1)),
                                Placeholder.unparsed("count", String.valueOf(view.slotCount))),
                        3);
        menu.button(
                11,
                named(Material.TROPICAL_FISH, msg.get("collection.display.menu_fish")),
                e -> openFishPicker(player, view, slot));
        menu.button(
                13,
                named(
                        Material.PRISMARINE_CRYSTALS,
                        msg.get(
                                "collection.display.menu_theme",
                                Placeholder.unparsed("slots", String.valueOf(view.themeSlots)))),
                e -> openThemePicker(player, view, slot));
        menu.button(
                15,
                named(Material.BARRIER, msg.get("collection.display.menu_clear")),
                e -> {
                    player.closeInventory();
                    var _ = manager.clear(player, plotId, slot);
                });
        menu.open(player);
    }

    private void openFishPicker(Player player, PlotView view, int slot) {
        long plotId = view.plot.plotId();
        var _ =
                manager.ownerCollection(view.plot.owner())
                        .thenAccept(
                                collection -> {
                                    if (!player.isOnline()) {
                                        return;
                                    }
                                    List<FishDef> fish = new ArrayList<>();
                                    for (FishId id : collection.keySet()) {
                                        api.content()
                                                .snapshot()
                                                .get(FishDef.TYPE, id.id())
                                                .filter(f -> !f.releaseOnly())
                                                .ifPresent(fish::add);
                                    }
                                    if (fish.isEmpty()) {
                                        api.messages()
                                                .send(player, "collection.display.no_collection");
                                        return;
                                    }
                                    fish.sort(Comparator.comparingInt(FishDef::no));
                                    MessageService msg = api.messages();
                                    new PagedMenu<>(
                                                    msg.get("collection.display.pick_title"),
                                                    6,
                                                    fish,
                                                    f ->
                                                            new PagedMenu.Entry(
                                                                    icon(f, collection.get(f.id())),
                                                                    e -> {
                                                                        player.closeInventory();
                                                                        var _ =
                                                                                manager.set(
                                                                                        player,
                                                                                        plotId,
                                                                                        slot,
                                                                                        f.id());
                                                                    }),
                                                    named(
                                                            Material.ARROW,
                                                            msg.get("collection.menu.previous")),
                                                    named(
                                                            Material.ARROW,
                                                            msg.get("collection.menu.next")))
                                            .show(player, 0);
                                });
    }

    private void openThemePicker(Player player, PlotView view, int slot) {
        if (!view.rows.containsKey(slot)) {
            api.messages().send(player, "collection.display.empty");
            return;
        }
        DisplayCaseRules rules = manager.rules();
        MessageService msg = api.messages();
        UUID owner = view.plot.owner();
        List<DisplayCaseRules.Theme> options = new ArrayList<>();
        options.add(rules.defaultTheme());
        for (DisplayCaseRules.Theme t : rules.themes()) {
            HarborId cosmetic = t.cosmetic();
            if (cosmetic != null && themes.owns(owner, cosmetic)) {
                options.add(t);
            }
        }
        long plotId = view.plot.plotId();
        Menu menu = new Menu(msg.get("collection.display.theme_title"), 3);
        for (int i = 0; i < options.size() && i < 27; i++) {
            DisplayCaseRules.Theme t = options.get(i);
            HarborId cosmetic = t.cosmetic();
            Material icon =
                    Optional.ofNullable(Material.matchMaterial(t.floor()))
                            .filter(Material::isItem)
                            .orElse(Material.SAND);
            Component label =
                    cosmetic == null
                            ? msg.get("collection.display.theme_default")
                            : msg.get(
                                    "collection.display.theme_name",
                                    Placeholder.unparsed("id", cosmetic.name()));
            menu.button(
                    i,
                    named(icon, label),
                    e -> {
                        player.closeInventory();
                        var _ = manager.setTheme(player, plotId, slot, cosmetic);
                    });
        }
        menu.open(player);
    }

    private ItemStack icon(FishDef fish, @Nullable CollectionEntry entry) {
        String baseItem =
                api.content()
                        .snapshot()
                        .single(Balance.TYPE)
                        .string("items.fish_base_item")
                        .orElse("minecraft:cod");
        Material material =
                Optional.ofNullable(Material.matchMaterial(baseItem)).orElse(Material.COD);
        ItemStack stack = ItemStack.of(material);
        ItemModels.apply(stack, fish.model());
        stack.setData(
                DataComponentTypes.ITEM_NAME,
                api.messages()
                        .get(
                                "item.name." + fish.grade().key(),
                                Placeholder.unparsed("name", fish.name())));
        if (entry != null) {
            stack.setData(
                    DataComponentTypes.LORE,
                    ItemLore.lore(
                            List.of(
                                    api.messages()
                                            .get(
                                                    "collection.display.pick_lore",
                                                    Placeholder.unparsed(
                                                            "size",
                                                            CollectionManager.fmt(entry.maxSize())),
                                                    Placeholder.unparsed(
                                                            "quality",
                                                            Quality.of(
                                                                            Math.clamp(
                                                                                    entry
                                                                                            .bestQuality(),
                                                                                    1,
                                                                                    5))
                                                                    .display()))
                                            .decorationIfAbsent(
                                                    TextDecoration.ITALIC,
                                                    TextDecoration.State.FALSE))));
        }
        return stack;
    }

    private static ItemStack named(Material material, Component name) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        return stack;
    }
}
