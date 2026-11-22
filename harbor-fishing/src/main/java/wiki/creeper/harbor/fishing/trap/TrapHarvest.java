package wiki.creeper.harbor.fishing.trap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.TrapAction;
import wiki.creeper.harbor.fishing.api.event.TrapHarvestEvent;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.session.FishingManager;

/**
 * Trap GUI actions: harvest (items created after the transaction commits, marked with the trap
 * source), adding/returning bait, removal. Catch item creation is shared with auto-retrieval. Main
 * thread only.
 */
final class TrapHarvest {

    private static final String SOURCE_TRAP = "trap";

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final FishingManager fishing;
    private final TrapManager traps;
    private final TrapBlocks blocks;
    private final TrapMenu menu;
    private final NamespacedKey sourceKey;

    TrapHarvest(
            Plugin plugin,
            HarborApi api,
            Tasks tasks,
            Logger log,
            FishingManager fishing,
            TrapManager traps,
            TrapBlocks blocks,
            TrapMenu menu) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.fishing = fishing;
        this.traps = traps;
        this.blocks = blocks;
        this.menu = menu;
        this.sourceKey = new NamespacedKey(plugin, "source");
    }

    boolean isTrapCatch(ItemStack stack) {
        return SOURCE_TRAP.equals(
                stack.getPersistentDataContainer().get(sourceKey, PersistentDataType.STRING));
    }

    /**
     * Harvests entries (items created after the transaction commits). Takes out only as many as
     * there are free inventory slots.
     *
     * @param menu reopen the trap screen afterwards (GUI)
     */
    CompletableFuture<TrapAction> harvest(
            Player player, TrapRow row, List<TrapCatch> entries, boolean menu) {
        int free = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.isEmpty()) {
                free++;
            }
        }
        if (free == 0) {
            api.messages().send(player, "fishing.trap.full");
            return CompletableFuture.completedFuture(
                    TrapAction.of(TrapAction.Outcome.INVENTORY_FULL, row.id()));
        }
        Set<String> uids = new HashSet<>();
        for (TrapCatch e : entries) {
            if (uids.size() >= free) {
                break;
            }
            uids.add(e.uid());
        }
        UUID uuid = player.getUniqueId();
        Instant now = Instant.now();
        return traps.track(
                        uuid,
                        api.database()
                                .async(c -> TrapRepository.take(c, row.id(), uuid, uids, now)))
                .handleAsync(
                        (taken, error) -> {
                            if (error != null || taken == null) {
                                log.warn("통발 수확 실패 ({}): {}", row.id(), String.valueOf(error));
                                api.messages().send(player, "fishing.trap.failed");
                                return TrapAction.of(TrapAction.Outcome.FAILED, row.id());
                            }
                            List<HarborItem.Fish> given = new ArrayList<>();
                            for (TrapCatch entry : taken) {
                                ItemStack stack = createCatch(player, entry);
                                if (stack == null) {
                                    continue;
                                }
                                api.items()
                                        .read(stack)
                                        .filter(i -> i instanceof HarborItem.Fish)
                                        .ifPresent(i -> given.add((HarborItem.Fish) i));
                                fishing.give(player, stack);
                            }
                            if (!given.isEmpty()) {
                                api.messages()
                                        .send(
                                                player,
                                                "fishing.trap.harvested",
                                                Placeholder.unparsed(
                                                        "count", String.valueOf(given.size())));
                                Bukkit.getPluginManager()
                                        .callEvent(
                                                new TrapHarvestEvent(
                                                        player, row.id(), row.region(), given));
                            }
                            if (menu) {
                                reopen(player, row.id());
                            }
                            return new TrapAction(TrapAction.Outcome.OK, row.id(), given.size());
                        },
                        tasks.main());
    }

    /**
     * Trap entry → species item (marked with the trap source). Null if the species left content.
     */
    @Nullable ItemStack createCatch(Player owner, TrapCatch entry) {
        return createCatch(api.players().get(owner).ref(), entry);
    }

    @Nullable ItemStack createCatch(
            wiki.creeper.harbor.common.model.PlayerRef owner, TrapCatch entry) {
        Optional<FishDef> def;
        try {
            def = api.content().snapshot().get(FishDef.TYPE, FishId.parse(entry.fish()).id());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (def.isEmpty()) {
            return null;
        }
        ItemStack stack =
                api.items()
                        .createFish(
                                def.get(),
                                entry.size(),
                                Quality.of(Math.clamp(entry.quality(), Quality.MIN, Quality.MAX)),
                                owner,
                                Instant.ofEpochMilli(entry.caughtAt()));
        stack.editPersistentDataContainer(
                pdc -> pdc.set(sourceKey, PersistentDataType.STRING, SOURCE_TRAP));
        return stack;
    }

    void reopen(Player player, long id) {
        if (!player.isOnline()) {
            return;
        }
        var _ =
                api.database()
                        .async(c -> TrapRepository.byId(c, id, false))
                        .whenCompleteAsync(
                                (row, error) -> {
                                    if (error == null && row.isPresent()) {
                                        menu.open(player, row.get());
                                    } else {
                                        player.closeInventory();
                                    }
                                },
                                tasks.main());
    }

    /** Puts bait from the inventory into the trap (excluding bait with uses and legendary bait). */
    CompletableFuture<TrapAction> addBait(Player player, TrapRow row, boolean menu) {
        if (api.players().frozen(player.getUniqueId())) {
            // The server move snapshot is already taken: bait taken now would come back
            return CompletableFuture.completedFuture(
                    TrapAction.of(TrapAction.Outcome.FAILED, row.id()));
        }
        FishingBalance b = traps.balance();
        int room = b.trapBaitCapacity() - row.bait();
        if (room <= 0) {
            api.messages().send(player, "fishing.trap.bait_full");
            return CompletableFuture.completedFuture(
                    TrapAction.of(TrapAction.Outcome.BAIT_FULL, row.id()));
        }
        int taken = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (ItemStack stack : contents) {
            if (taken >= room || stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<ItemDef> def = traps.fishingItem(stack);
            if (def.isEmpty()
                    || def.get().type() != ItemDef.Type.BAIT
                    || def.get().stats().getOrDefault("legend_only", 0.0) > 0
                    || def.get().stats().getOrDefault("uses", 0.0) > 1) {
                continue;
            }
            int n = Math.min(room - taken, stack.getAmount());
            stack.setAmount(stack.getAmount() - n);
            taken += n;
        }
        if (taken == 0) {
            api.messages().send(player, "fishing.trap.no_bait");
            return CompletableFuture.completedFuture(
                    TrapAction.of(TrapAction.Outcome.NO_BAIT, row.id()));
        }
        int amount = taken;
        UUID uuid = player.getUniqueId();
        return traps.track(
                        uuid,
                        api.database()
                                .async(
                                        c ->
                                                TrapRepository.addBait(
                                                        c,
                                                        row.id(),
                                                        uuid,
                                                        amount,
                                                        b.trapBaitCapacity())))
                .handleAsync(
                        (ok, error) -> {
                            if (error != null || !Boolean.TRUE.equals(ok)) {
                                refundBait(player, amount);
                                api.messages().send(player, "fishing.trap.failed");
                                return TrapAction.of(TrapAction.Outcome.FAILED, row.id());
                            }
                            api.messages()
                                    .send(
                                            player,
                                            "fishing.trap.bait_added",
                                            Placeholder.unparsed("count", String.valueOf(amount)));
                            if (menu) {
                                reopen(player, row.id());
                            }
                            return new TrapAction(TrapAction.Outcome.OK, row.id(), amount);
                        },
                        tasks.main());
    }

    /**
     * Bait refund (which bait was used is not recorded, so the default bait
     * fishing.trap.refund_bait_item, proposed value).
     */
    private void refundBait(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        refundBaitItems(amount).forEach(s -> fishing.give(player, s));
    }

    List<ItemStack> refundBaitItems(int amount) {
        String id =
                api.content()
                        .snapshot()
                        .single(Balance.TYPE)
                        .string("fishing.trap.refund_bait_item")
                        .orElse("harbor:item/bait/earthworm");
        List<ItemStack> result = new ArrayList<>();
        try {
            api.content()
                    .snapshot()
                    .get(ItemDef.TYPE, ItemId.parse(id).id())
                    .ifPresent(
                            def -> {
                                int left = amount;
                                while (left > 0) {
                                    int n = Math.min(64, left);
                                    result.add(api.items().create(def, n));
                                    left -= n;
                                }
                            });
        } catch (IllegalArgumentException e) {
            log.warn("fishing.trap.refund_bait_item 형식이 틀렸습니다: {}", id);
        }
        return result;
    }

    /** Removal: only when empty. Returns the trap item and the remaining bait. */
    CompletableFuture<TrapAction> remove(Player player, TrapRow row, boolean menu) {
        if (!row.contents().isEmpty()) {
            api.messages().send(player, "fishing.trap.not_empty");
            return CompletableFuture.completedFuture(
                    TrapAction.of(TrapAction.Outcome.NOT_EMPTY, row.id()));
        }
        UUID uuid = player.getUniqueId();
        return traps.track(
                        uuid,
                        api.database().async(c -> TrapRepository.deleteEmpty(c, row.id(), uuid)))
                .handleAsync(
                        (bait, error) -> {
                            if (error != null || bait == null || bait < 0) {
                                api.messages().send(player, "fishing.trap.failed");
                                return TrapAction.of(TrapAction.Outcome.FAILED, row.id());
                            }
                            if (menu) {
                                player.closeInventory();
                            }
                            blocks.demolish(row.world(), row.x(), row.y(), row.z(), row.id());
                            api.content()
                                    .snapshot()
                                    .get(ItemDef.TYPE, row.item().id())
                                    .ifPresent(
                                            def ->
                                                    fishing.give(
                                                            player, api.items().create(def, 1)));
                            refundBait(player, bait);
                            api.messages().send(player, "fishing.trap.removed");
                            return new TrapAction(TrapAction.Outcome.OK, row.id(), bait);
                        },
                        tasks.main());
    }
}
