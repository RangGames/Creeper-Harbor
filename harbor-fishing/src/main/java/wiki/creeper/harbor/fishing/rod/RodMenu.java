package wiki.creeper.harbor.fishing.rod;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.fishing.Locs;

/**
 * /낚싯대: attach and detach the line, float and reel of the rod in the main hand (proposed UI for "8.
 * System design" 2 rod attachments). A Dialog lists the attached parts (choose one to detach it,
 * the item comes back) and the parts carried in the inventory (choose one to attach it; the part
 * already in that slot comes back). Main thread only.
 */
public final class RodMenu {

    private record Option(ItemDef.Type slot, ItemDef def, boolean detach) {}

    private final HarborApi api;
    private final RodParts rods;
    private final Predicate<Player> busy;

    /**
     * @param busy whether the player is fishing (parts cannot change mid-cast or mid-fight)
     */
    public RodMenu(HarborApi api, RodParts rods, Predicate<Player> busy) {
        this.api = api;
        this.rods = rods;
        this.busy = busy;
    }

    public void open(Player player) {
        MessageService msg = api.messages();
        PlayerInventory inv = player.getInventory();
        ItemStack rod = inv.getItemInMainHand();
        if (!RodParts.isRod(rod)) {
            msg.send(player, "fishing.rod.not_holding");
            return;
        }
        if (busy.test(player)) {
            msg.send(player, "fishing.rod.busy");
            return;
        }
        int heldSlot = inv.getHeldItemSlot();
        ItemStack snapshot = rod.clone();
        Map<ItemDef.Type, ItemDef> attached = rods.parts(rod);

        List<Component> body = new ArrayList<>();
        body.add(
                msg.get(
                        "fishing.rod.menu.rod",
                        Placeholder.component("name", rod.effectiveName())));
        rods.enchants(rod)
                .forEach(
                        (e, level) ->
                                body.add(
                                        msg.get(
                                                "fishing.rod.lore.enchant",
                                                Placeholder.component("name", rods.enchantName(e)),
                                                Placeholder.component(
                                                        "level", rods.levelName(level)))));
        for (ItemDef.Type slot : RodParts.SLOTS) {
            ItemDef part = attached.get(slot);
            body.add(
                    part == null
                            ? msg.get(
                                    "fishing.rod.menu.slot_empty",
                                    Placeholder.component("slot", rods.slotName(slot)))
                            : msg.get(
                                    "fishing.rod.lore.part",
                                    Placeholder.component("slot", rods.slotName(slot)),
                                    Placeholder.component("name", rods.itemName(part))));
        }
        body.add(msg.get("fishing.rod.menu.body"));

        List<Option> options = new ArrayList<>();
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (ItemDef.Type slot : RodParts.SLOTS) {
            ItemDef part = attached.get(slot);
            if (part != null) {
                options.add(new Option(slot, part, true));
                choices.add(
                        new Dialogs.Choice(
                                msg.get(
                                        "fishing.rod.menu.detach",
                                        Placeholder.component("slot", rods.slotName(slot)),
                                        Placeholder.component("name", rods.itemName(part))),
                                tooltip(api.items().create(part, 1))));
            }
        }
        for (Map.Entry<ItemId, ItemDef> e : carried(inv).entrySet()) {
            ItemDef def = e.getValue();
            ItemDef current = attached.get(def.type());
            if (current != null && current.id().equals(def.id())) {
                continue;
            }
            options.add(new Option(def.type(), def, false));
            choices.add(
                    new Dialogs.Choice(
                            msg.get(
                                    "fishing.rod.menu.attach",
                                    Placeholder.component("slot", rods.slotName(def.type())),
                                    Placeholder.component("name", rods.itemName(def))),
                            tooltip(api.items().create(def, 1))));
        }
        if (options.isEmpty()) {
            body.add(msg.get("fishing.rod.menu.empty"));
        }
        choices.add(new Dialogs.Choice(msg.get("fishing.rod.menu.close"), Component.empty()));

        api.dialogs()
                .choose(
                        player,
                        msg.get("fishing.rod.menu.title"),
                        body,
                        choices,
                        1,
                        index -> {
                            if (index < 0 || index >= options.size() || !player.isOnline()) {
                                return;
                            }
                            ItemStack now = inv.getItemInMainHand();
                            if (inv.getHeldItemSlot() != heldSlot || !now.isSimilar(snapshot)) {
                                msg.send(player, "fishing.rod.changed");
                                return;
                            }
                            if (busy.test(player)) {
                                msg.send(player, "fishing.rod.busy");
                                return;
                            }
                            Option o = options.get(index);
                            boolean done =
                                    o.detach()
                                            ? detach(player, now, o.slot())
                                            : attach(player, now, o.def());
                            if (done) {
                                open(player);
                            }
                        });
    }

    /** Takes one part of the definition from the inventory and attaches it (swapping). */
    boolean attach(Player player, ItemStack rod, ItemDef def) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            ItemStack stack = storage[i];
            if (stack == null || i == inv.getHeldItemSlot() || !isPart(stack, def.id())) {
                continue;
            }
            stack.setAmount(stack.getAmount() - 1);
            inv.setItem(i, stack.getAmount() <= 0 ? null : stack);
            Optional<ItemId> previous = rods.partId(rod, def.type());
            rods.setPart(rod, def.type(), def.id());
            inv.setItemInMainHand(rod);
            previous.ifPresent(id -> giveBack(player, id));
            api.messages()
                    .send(
                            player,
                            "fishing.rod.attached",
                            Placeholder.component("name", rods.itemName(def)),
                            Placeholder.component("slot", rods.slotName(def.type())));
            return true;
        }
        api.messages().send(player, "fishing.rod.no_part");
        return false;
    }

    /** Detaches a slot and returns the part. */
    boolean detach(Player player, ItemStack rod, ItemDef.Type slot) {
        Optional<ItemId> part = rods.partId(rod, slot);
        if (part.isEmpty()) {
            return false;
        }
        rods.setPart(rod, slot, null);
        player.getInventory().setItemInMainHand(rod);
        giveBack(player, part.get());
        api.content()
                .snapshot()
                .get(ItemDef.TYPE, part.get().id())
                .ifPresent(
                        def ->
                                api.messages()
                                        .send(
                                                player,
                                                "fishing.rod.detached",
                                                Placeholder.component("name", rods.itemName(def))));
        return true;
    }

    // ------------------------------------------------------------------ Internal

    private Map<ItemId, ItemDef> carried(PlayerInventory inv) {
        Map<ItemId, ItemDef> result = new LinkedHashMap<>();
        for (ItemStack stack : inv.getStorageContents()) {
            partDef(stack).ifPresent(d -> result.putIfAbsent(d.id(), d));
        }
        return result;
    }

    private Optional<ItemDef> partDef(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Plain)
                .map(i -> (HarborItem.Plain) i)
                .flatMap(p -> api.content().snapshot().get(ItemDef.TYPE, p.id().id()))
                .filter(d -> RodParts.SLOTS.contains(d.type()));
    }

    private boolean isPart(ItemStack stack, ItemId id) {
        return partDef(stack).map(d -> d.id().equals(id)).orElse(false);
    }

    /** Returns a detached part (dropped at the feet for the owner when the inventory is full). */
    private void giveBack(Player player, ItemId id) {
        api.content()
                .snapshot()
                .get(ItemDef.TYPE, id.id())
                .ifPresent(
                        def -> {
                            ItemStack stack = api.items().create(def, 1);
                            for (ItemStack left : player.getInventory().addItem(stack).values()) {
                                Item dropped = player.getWorld().dropItem(Locs.of(player), left);
                                dropped.setOwner(player.getUniqueId());
                            }
                        });
    }

    private static Component tooltip(ItemStack sample) {
        ItemLore lore = sample.getData(DataComponentTypes.LORE);
        return lore == null
                ? Component.empty()
                : Component.join(JoinConfiguration.newlines(), lore.lines());
    }
}
