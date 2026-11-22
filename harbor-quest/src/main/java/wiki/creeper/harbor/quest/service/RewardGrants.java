package wiki.creeper.harbor.quest.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.jobs.api.ExpGrant;
import wiki.creeper.harbor.jobs.api.ExpSource;
import wiki.creeper.harbor.npc.api.AffinitySource;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.Rewards;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.RefIds;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * Reward payout steps 3~4 (docs 20 6.4): currency via the ledger reference ID unique key;
 * reputation, flags, titles and chronicle idempotently; experience, affinity, hints and items (the
 * grant bundle) are given after being recorded in the completion transaction. When everything is
 * done, done is recorded in paid. See {@link RewardPayer} for the order and recovery rules.
 */
final class RewardGrants {

    private final QuestEngine engine;

    RewardGrants(QuestEngine engine) {
        this.engine = engine;
    }

    /**
     * Steps 3~4.
     *
     * @param fresh just completed (gives the grant bundle). On recovery it is given only if grant
     *     has not been recorded
     */
    void pay(UUID uuid, QuestDef def, QuestEntry entry, boolean fresh) {
        Progress progress = entry.progress();
        Progress.Snapshot snap = progress.snap();
        Rewards rewards = def.rewards();
        String ref = RefIds.reward(def.id(), entry.season(), progress.cycle());
        List<CompletableFuture<Boolean>> idempotent = new ArrayList<>();
        // step 3: currency (ledger reference ID unique key)
        if (snap.gold() > 0) {
            idempotent.add(
                    engine.api
                            .currency()
                            .give(uuid, Currency.GOLD, snap.gold(), LedgerReason.QUEST_REWARD, ref)
                            .thenApply(r -> r.isOk() || isDuplicate(r)));
        }
        if (snap.mulbineul() > 0) {
            idempotent.add(
                    engine.api
                            .currency()
                            .give(
                                    uuid,
                                    Currency.MULBINEUL,
                                    snap.mulbineul(),
                                    LedgerReason.QUEST_REWARD,
                                    ref)
                            .thenApply(r -> r.isOk() || isDuplicate(r)));
        }
        Player player = Bukkit.getPlayer(uuid);
        // step 4: idempotent rewards (reputation refId, flags, titles, chronicle)
        if (snap.reputation() > 0) {
            idempotent.add(
                    engine.deps
                            .reputation()
                            .add(uuid, snap.reputation(), RewardPayer.source(def.type()), ref)
                            .thenApply(
                                    r ->
                                            r.ok()
                                                    || r.status()
                                                            == wiki.creeper.harbor.npc.api
                                                                    .ReputationResult.Status
                                                                    .DUPLICATE
                                                    || r.status()
                                                            == wiki.creeper.harbor.npc.api
                                                                    .ReputationResult.Status
                                                                    .CAPPED));
        }
        idempotent.add(engine.setCompletionFlag(uuid, def).thenApply(v -> true));
        for (Rewards.FlagReward f : rewards.flags()) {
            CompletableFuture<Void> set =
                    f.server()
                            ? engine.api.flags().setServer(f.flag(), "1", f.seasonal())
                            : engine.api
                                    .flags()
                                    .setPlayer(
                                            uuid,
                                            f.seasonal()
                                                    ? FlagService.Scope.SEASON
                                                    : FlagService.Scope.PERM,
                                            f.flag());
            idempotent.add(set.thenApply(v -> true));
        }
        String title = rewards.title();
        if (title != null) {
            idempotent.add(
                    engine.deps
                            .titles()
                            .grant(uuid, TitleId.of(title), RefIds.title(def.id()))
                            .thenApply(g -> g.owned()));
        }
        String piece = rewards.chronicle();
        if (piece != null) {
            idempotent.add(engine.chronicle().award(uuid, piece));
        }
        // grant bundle: experience, affinity, hints, items
        List<String> itemNames = new ArrayList<>();
        boolean grantNow = fresh || !progress.paid(Progress.PAID_GRANT);
        CompletableFuture<Boolean> grantDone;
        if (!grantNow) {
            grantDone = CompletableFuture.completedFuture(true);
        } else if (player == null) {
            // quit after the completion transaction: clear the grant record so it is given on the
            // next join
            Progress undo =
                    new Progress(
                            progress.version(),
                            progress.counts(),
                            progress.index(),
                            progress.cycle(),
                            progress.snap(),
                            progress.paid().stream()
                                    .filter(p -> !p.equals(Progress.PAID_GRANT))
                                    .toList(),
                            progress.acceptedAt());
            grantDone =
                    engine.serial(
                            uuid,
                            c -> {
                                QuestRepository.savePaid(c, uuid, entry.season(), def.id(), undo);
                                return false;
                            });
        } else if (!fresh) {
            // recovery: commit the grant record first, then give (prevents duplication)
            Progress marked = progress.withPaid(Progress.PAID_GRANT);
            grantDone =
                    engine.serial(
                                    uuid,
                                    c -> {
                                        QuestRepository.savePaid(
                                                c, uuid, entry.season(), def.id(), marked);
                                        return true;
                                    })
                            .thenApply(
                                    ok -> {
                                        if (player.isOnline()) {
                                            grant(player, def, snap, ref, itemNames);
                                        }
                                        return true;
                                    });
        } else {
            grant(player, def, snap, ref, itemNames);
            grantDone = CompletableFuture.completedFuture(true);
        }
        idempotent.add(grantDone);
        var _ =
                CompletableFuture.allOf(idempotent.toArray(CompletableFuture[]::new))
                        .handleAsync(
                                (v, error) -> {
                                    boolean all =
                                            error == null
                                                    && idempotent.stream()
                                                            .allMatch(
                                                                    f ->
                                                                            Boolean.TRUE.equals(
                                                                                    f.getNow(
                                                                                            false)));
                                    if (all) {
                                        Progress paid =
                                                progress.withPaid(Progress.PAID_GRANT)
                                                        .withPaid(Progress.PAID_DONE);
                                        QuestEntry finished = entry.withProgress(paid);
                                        engine.api
                                                .players()
                                                .get(uuid)
                                                .ifPresent(
                                                        p ->
                                                                p.set(
                                                                        QuestEngine.COMPONENT,
                                                                        p.get(QuestEngine.COMPONENT)
                                                                                .with(finished)));
                                        var _ =
                                                engine.serial(
                                                        uuid,
                                                        c -> {
                                                            QuestRepository.savePaid(
                                                                    c,
                                                                    uuid,
                                                                    entry.season(),
                                                                    def.id(),
                                                                    paid);
                                                            return true;
                                                        });
                                    } else {
                                        engine.log.warn(
                                                "퀘스트 보상 일부를 주지 못했습니다 (다음 접속 때 다시 시도): {} {}",
                                                uuid,
                                                def.id());
                                    }
                                    return null;
                                },
                                engine.tasks.main());
        if (fresh) {
            engine.payer()
                    .effects()
                    .afterComplete(
                            uuid,
                            def,
                            entry,
                            engine.payer().effects().summary(def, snap, itemNames));
        }
    }

    private static boolean isDuplicate(Result<Long, LedgerError> r) {
        return r instanceof Result.Err<Long, LedgerError> err
                && err.error() == LedgerError.DUPLICATE;
    }

    /** Experience, affinity, hints and items (main thread, online). */
    void grant(
            Player player,
            QuestDef def,
            Progress.Snapshot snap,
            String ref,
            List<String> itemNames) {
        UUID uuid = player.getUniqueId();
        Rewards rewards = def.rewards();
        if (snap.exp() > 0) {
            engine.deps.jobs().grantExp(uuid, new ExpGrant(ExpSource.QUEST, snap.exp(), null, ref));
        }
        for (Rewards.AffinityReward a : rewards.affinity()) {
            engine.deps.affinity().add(uuid, a.npc(), a.value(), AffinitySource.QUEST);
        }
        for (Rewards.HintReward h : rewards.hints()) {
            RegionId region =
                    h.region() != null ? h.region() : engine.highestRegion(uuid).orElse(null);
            for (int i = 0; i < h.count(); i++) {
                if (engine.deps.hints().grantRandom(uuid, region, "quest").isEmpty()) {
                    // substitute gold when there is no hint left to give (docs 20 4.4 grantRandom
                    // rule, proposed value)
                    long gold = engine.boardRules().hintFallbackGold();
                    if (gold > 0) {
                        var _ =
                                engine.api
                                        .currency()
                                        .give(
                                                uuid,
                                                Currency.GOLD,
                                                gold,
                                                LedgerReason.QUEST_REWARD,
                                                RefIds.fit(ref + ":h" + i));
                    }
                }
            }
        }
        for (Rewards.ItemReward item : rewards.items()) {
            Optional<ItemDef> idef = engine.snapshot().get(ItemDef.TYPE, item.item().id());
            if (idef.isEmpty()) {
                engine.log.warn("보상 아이템 정의가 없습니다: {}", item.item());
                continue;
            }
            ItemStack stack = engine.api.items().create(idef.get(), item.amount());
            itemNames.add(item.item() + " x" + item.amount());
            engine.payer().delivery().deliverItem(player, stack, ref);
        }
    }
}
