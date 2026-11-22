package wiki.creeper.harbor.boss.encounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.boss.content.BossBalance;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.boss.data.PendingReward;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.fishing.api.CatchOptions;
import wiki.creeper.harbor.fishing.api.CatchResult;

/**
 * Delivery of pending boss rewards (docs 20 10.4 steps 4-5): puts pending rows into the inventory,
 * marks them in PDC harbor:boss_delivered, saves, then closes the rows. Main thread only.
 */
final class RewardDelivery {

    private final EncounterManager mgr;
    private final HarborApi api;

    RewardDelivery(EncounterManager mgr) {
        this.mgr = mgr;
        this.api = mgr.api();
    }

    /**
     * Delivers unclaimed rewards (steps 4-5). IDs already in the PDC only close their row. If the
     * inventory is short on space, the rest stays to be claimed again with /boss claim.
     *
     * @param notifyEmpty whether to notify even when there is nothing to receive (/boss claim)
     */
    void deliverPending(Player player, boolean notifyEmpty) {
        UUID uuid = player.getUniqueId();
        var _ =
                api.database()
                        .async(c -> BossRepository.undelivered(c, uuid))
                        .thenAcceptAsync(
                                list -> deliver(player, list, notifyEmpty), mgr.tasks().main())
                        .exceptionally(
                                err -> {
                                    mgr.log()
                                            .warn(
                                                    "미지급 보상 조회 실패 {}: {}",
                                                    uuid,
                                                    String.valueOf(err.getMessage()));
                                    return null;
                                });
    }

    CompletableFuture<List<PendingReward>> pending(UUID player) {
        CompletableFuture<List<PendingReward>> out = new CompletableFuture<>();
        var _ =
                api.database()
                        .async(c -> BossRepository.undelivered(c, player))
                        .whenCompleteAsync(
                                (list, err) -> {
                                    if (err != null) {
                                        out.completeExceptionally(err);
                                    } else {
                                        out.complete(list);
                                    }
                                },
                                mgr.tasks().main());
        return out;
    }

    private void deliver(Player player, List<PendingReward> list, boolean notifyEmpty) {
        if (!player.isOnline()) {
            return;
        }
        if (list.isEmpty()) {
            if (notifyEmpty) {
                api.messages().send(player, "boss.reward.nothing");
            }
            return;
        }
        Set<String> done = BossItems.delivered(player);
        List<UUID> close = new ArrayList<>();
        List<String> marked = new ArrayList<>();
        boolean full = false;
        for (PendingReward r : list) {
            if (done.contains(r.id().toString())) {
                close.add(r.id());
                continue;
            }
            List<ItemStack> stacks = build(player, r);
            if (!fits(player, stacks)) {
                full = true;
                continue;
            }
            for (ItemStack s : stacks) {
                var _ = player.getInventory().addItem(s);
                api.messages()
                        .send(
                                player,
                                "boss.reward.item",
                                Placeholder.component("item", s.effectiveName()),
                                Placeholder.unparsed("count", String.valueOf(s.getAmount())));
            }
            marked.add(r.id().toString());
            close.add(r.id());
        }
        if (!marked.isEmpty()) {
            BossItems.markDelivered(player, marked);
        }
        if (full) {
            api.messages().send(player, "boss.reward.inventory_full");
        }
        if (!close.isEmpty()) {
            // Save the delivered items and PDC marker first, then close the DB reward rows. In the
            // reverse order, if the server died after closing, the inventory would roll back to
            // before delivery and the rewards would be lost. If the save fails, the rows stay open
            // and the next delivery handles them via the PDC marker (if in memory) or by granting
            // again. Vanilla saveData() does not help because the Harbor save is the source of
            // truth
            UUID uuid = player.getUniqueId();
            var _ =
                    api.players()
                            .saveNow(uuid)
                            .thenCompose(
                                    v ->
                                            api.database()
                                                    .asyncVoid(
                                                            c ->
                                                                    BossRepository.markDelivered(
                                                                            c, close)))
                            .exceptionally(
                                    e -> {
                                        mgr.log()
                                                .warn(
                                                        "보스 보상 지급 기록 실패 {}: {}",
                                                        uuid,
                                                        String.valueOf(e.getMessage()));
                                        return null;
                                    });
        }
    }

    private List<ItemStack> build(Player player, PendingReward r) {
        List<ItemStack> out = new ArrayList<>();
        for (PendingReward.Entry entry : r.entries()) {
            try {
                if ("fish".equals(entry.type())) {
                    BossBalance bb = mgr.balance();
                    Optional<CatchResult> fish =
                            mgr.fishing()
                                    .createCatch(
                                            player,
                                            new FishId(HarborId.parse(entry.id())),
                                            new CatchOptions(
                                                    bb.captureQualityStars(),
                                                    bb.captureLuck(),
                                                    true));
                    fish.map(CatchResult::stack)
                            .ifPresent(
                                    s -> {
                                        if (s != null) {
                                            out.add(s);
                                        }
                                    });
                    continue;
                }
                Optional<ItemStack> stack =
                        mgr.items().create(new ItemId(HarborId.parse(entry.id())), entry.count());
                if (stack.isEmpty()) {
                    mgr.log().warn("보상 아이템이 콘텐츠에 없습니다: {}", entry.id());
                    continue;
                }
                ItemStack s = stack.get();
                String uid = entry.uid();
                if (uid != null) {
                    s.editPersistentDataContainer(
                            pdc ->
                                    pdc.set(
                                            BossItems.BOSS_UID,
                                            org.bukkit.persistence.PersistentDataType.STRING,
                                            uid));
                }
                out.add(s);
            } catch (IllegalArgumentException e) {
                mgr.log().warn("보상 항목 형식이 틀렸습니다: {}", entry);
            }
        }
        return out;
    }

    /** Whether everything fits in the inventory (judged conservatively by empty slot count). */
    private static boolean fits(Player player, List<ItemStack> stacks) {
        int empty = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.isEmpty()) {
                empty++;
            }
        }
        return empty >= stacks.size();
    }
}
