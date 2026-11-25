package wiki.creeper.harbor.fishing.session;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.MessageService;

/**
 * Which bait stack a cast uses (proposed). Bait held in the off hand comes first; otherwise the
 * bait type chosen with /미끼 is taken from the inventory, a partly used lure first so uses run down
 * one item at a time. Items never leave the inventory, so inventory sync and server moves need
 * nothing extra. The choice is a permanent player flag holding the item ID. Main thread only.
 */
final class BaitStacks {

    /** Player flag with the chosen bait item ID. */
    static final String FLAG = "fishing/bait";

    /** {@link Stack#slot()} of the off hand. */
    static final int OFF_HAND = -1;

    /**
     * @param slot storage slot index, or {@link #OFF_HAND}
     */
    record Stack(int slot, ItemStack item, ItemDef def) {}

    private final HarborApi api;
    private final NamespacedKey uses;

    BaitStacks(Plugin plugin, HarborApi api) {
        this.api = api;
        this.uses = new NamespacedKey(plugin, "bait_uses");
    }

    /** PDC key with the remaining uses of a lure stack. */
    NamespacedKey usesKey() {
        return uses;
    }

    /** The bait the next cast uses (null: no bait). */
    @Nullable Stack current(Player player) {
        ItemStack off = player.getInventory().getItemInOffHand();
        Optional<ItemDef> offDef = bait(off);
        if (offDef.isPresent()) {
            return new Stack(OFF_HAND, off, offDef.get());
        }
        Optional<ItemId> chosen = chosen(player.getUniqueId());
        if (chosen.isEmpty()) {
            return null;
        }
        ItemStack[] contents = player.getInventory().getStorageContents();
        @Nullable Stack first = null;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            Optional<ItemDef> def = bait(item).filter(d -> d.id().equals(chosen.get()));
            if (def.isEmpty()) {
                continue;
            }
            Stack stack = new Stack(i, item, def.get());
            if (item.getPersistentDataContainer().has(uses, PersistentDataType.INTEGER)) {
                return stack;
            }
            if (first == null) {
                first = stack;
            }
        }
        return first;
    }

    /** Bait type chosen with /미끼 (whether or not any is left). */
    Optional<ItemId> chosen(UUID player) {
        return api.flags()
                .playerValue(player, FLAG)
                .flatMap(
                        raw -> {
                            try {
                                return Optional.of(ItemId.parse(raw));
                            } catch (IllegalArgumentException e) {
                                return Optional.empty();
                            }
                        });
    }

    /** Saves the choice (null clears it). The flag cache is updated when it completes. */
    CompletableFuture<Void> choose(UUID player, @Nullable ItemId bait) {
        return bait == null
                ? api.flags().unsetPlayer(player, FLAG)
                : api.flags().setPlayer(player, FlagService.Scope.PERM, FLAG, bait.toString());
    }

    /** Item definition when the stack is a Harbor bait item. */
    Optional<ItemDef> bait(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Plain)
                .map(i -> (HarborItem.Plain) i)
                .flatMap(p -> api.content().snapshot().get(ItemDef.TYPE, p.id().id()))
                .filter(d -> d.type() == ItemDef.Type.BAIT);
    }

    /** Uses per item from the item stats (0 or 1: deducted per use, no counter). */
    static int usesPerItem(ItemDef def) {
        return (int) Math.round(def.stats().getOrDefault("uses", 0.0));
    }

    /** Remaining uses of the front item of a lure stack (0 for bait without uses). */
    int usesLeft(Stack stack) {
        int perItem = usesPerItem(stack.def());
        if (perItem <= 1) {
            return 0;
        }
        Integer stored =
                stack.item().getPersistentDataContainer().get(uses, PersistentDataType.INTEGER);
        return stored == null ? perItem : stored;
    }

    /**
     * Action bar on a cast: the bait in use (and lure uses left), or that there is none and the
     * bite wait penalty, with a hint when the chosen bait ran out.
     *
     * @param noBaitWaitMult balance.yml fishing.no_bait_wait (e.g. 1.2)
     */
    void castNotice(Player player, @Nullable ItemId bait, double noBaitWaitMult) {
        MessageService msg = api.messages();
        if (bait != null) {
            Stack stack = current(player);
            String name = stack == null ? bait.toString() : stack.def().name();
            int left = stack == null ? 0 : usesLeft(stack);
            Component uses =
                    left > 0
                            ? msg.get(
                                    "fishing.bait.cast_uses",
                                    Placeholder.unparsed("left", String.valueOf(left)))
                            : Component.empty();
            api.ui()
                    .actionBar(
                            player,
                            msg.get(
                                    "fishing.bait.cast",
                                    Placeholder.unparsed("name", name),
                                    Placeholder.component("uses", uses)));
            return;
        }
        String penalty = String.format(Locale.ROOT, "%d", Math.round((noBaitWaitMult - 1) * 100));
        Optional<ItemDef> chosen =
                chosen(player.getUniqueId())
                        .flatMap(id -> api.content().snapshot().get(ItemDef.TYPE, id.id()));
        api.ui()
                .actionBar(
                        player,
                        chosen.isPresent()
                                ? msg.get(
                                        "fishing.bait.out",
                                        Placeholder.unparsed("name", chosen.get().name()),
                                        Placeholder.unparsed("percent", penalty))
                                : msg.get(
                                        "fishing.bait.none",
                                        Placeholder.unparsed("percent", penalty)));
    }

    /** Writes a changed stack back to its slot (amount 0 clears the slot). */
    void write(Player player, Stack stack) {
        ItemStack item = stack.item();
        @Nullable ItemStack value = item.getAmount() <= 0 ? null : item;
        if (stack.slot() == OFF_HAND) {
            player.getInventory().setItemInOffHand(value);
        } else {
            player.getInventory().setItem(stack.slot(), value);
        }
    }
}
