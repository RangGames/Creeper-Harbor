package wiki.creeper.harbor.fishing.session;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MessageService;

/**
 * /미끼: choose which bait type in the inventory casts use, without holding it in the off hand
 * (proposed). Lists the bait types the player carries with their counts and effects (the item
 * lore). Off-hand bait still comes first ({@link BaitStacks}).
 */
final class BaitMenu {

    private record Entry(ItemDef def, ItemStack sample, int count) {}

    private final HarborApi api;
    private final BaitStacks baits;

    BaitMenu(HarborApi api, BaitStacks baits) {
        this.api = api;
        this.baits = baits;
    }

    void open(Player player) {
        MessageService msg = api.messages();
        UUID uuid = player.getUniqueId();
        Map<ItemId, Entry> carried = new LinkedHashMap<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            collect(carried, item);
        }
        collect(carried, player.getInventory().getItemInOffHand());
        Optional<ItemId> chosen = baits.chosen(uuid);
        if (carried.isEmpty() && chosen.isEmpty()) {
            msg.send(player, "fishing.bait.menu.empty");
            return;
        }

        List<Component> body = new ArrayList<>();
        Optional<ItemDef> chosenDef =
                chosen.flatMap(id -> api.content().snapshot().get(ItemDef.TYPE, id.id()));
        body.add(
                chosenDef.isPresent()
                        ? msg.get(
                                "fishing.bait.menu.current",
                                Placeholder.component("name", name(chosenDef.get())))
                        : msg.get("fishing.bait.menu.current_none"));
        baits.bait(player.getInventory().getItemInOffHand())
                .ifPresent(
                        d ->
                                body.add(
                                        msg.get(
                                                "fishing.bait.menu.offhand",
                                                Placeholder.component("name", name(d)))));
        body.add(msg.get("fishing.bait.menu.body"));

        List<Entry> entries = List.copyOf(carried.values());
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (Entry e : entries) {
            boolean selected = chosen.map(e.def().id()::equals).orElse(false);
            choices.add(
                    new Dialogs.Choice(
                            msg.get(
                                    selected
                                            ? "fishing.bait.menu.entry_selected"
                                            : "fishing.bait.menu.entry",
                                    Placeholder.component("name", name(e.def())),
                                    Placeholder.unparsed("count", String.valueOf(e.count()))),
                            tooltip(e.sample())));
        }
        int clearIndex = chosen.isPresent() ? choices.size() : -1;
        if (chosen.isPresent()) {
            choices.add(
                    new Dialogs.Choice(
                            msg.get("fishing.bait.menu.clear"),
                            msg.get("fishing.bait.menu.clear_tip")));
        }
        choices.add(new Dialogs.Choice(msg.get("fishing.bait.menu.close"), Component.empty()));

        api.dialogs()
                .choose(
                        player,
                        msg.get("fishing.bait.menu.title"),
                        body,
                        choices,
                        1,
                        index -> {
                            if (index >= 0 && index < entries.size()) {
                                ItemDef def = entries.get(index).def();
                                save(player, def.id(), "fishing.bait.chosen", name(def));
                            } else if (index == clearIndex) {
                                save(player, null, "fishing.bait.cleared", Component.empty());
                            }
                        });
    }

    private void save(Player player, @Nullable ItemId bait, String key, Component name) {
        var _ =
                baits.choose(player.getUniqueId(), bait)
                        .handle(
                                (ok, error) -> {
                                    api.messages()
                                            .send(
                                                    player,
                                                    error == null
                                                            ? key
                                                            : "fishing.bait.save_failed",
                                                    Placeholder.component("name", name));
                                    return null;
                                });
    }

    private void collect(Map<ItemId, Entry> carried, ItemStack item) {
        baits.bait(item)
                .ifPresent(
                        def ->
                                carried.merge(
                                        def.id(),
                                        new Entry(def, item, item.getAmount()),
                                        (a, b) ->
                                                new Entry(
                                                        a.def(),
                                                        a.sample(),
                                                        a.count() + b.count())));
    }

    private Component name(ItemDef def) {
        return api.messages()
                .get("item.name." + def.grade().key(), Placeholder.unparsed("name", def.name()));
    }

    /** The item's lore (description and effect lines) as the button tooltip. */
    private static Component tooltip(ItemStack sample) {
        ItemLore lore = sample.getData(DataComponentTypes.LORE);
        return lore == null
                ? Component.empty()
                : Component.join(JoinConfiguration.newlines(), lore.lines());
    }
}
