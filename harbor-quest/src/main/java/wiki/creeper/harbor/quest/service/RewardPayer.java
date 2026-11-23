package wiki.creeper.harbor.quest.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.jobs.api.AdvanceResult;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.api.event.QuestStateChangedEvent;
import wiki.creeper.harbor.quest.content.BoardRules;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.content.Rewards;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.DeliverPlan;
import wiki.creeper.harbor.quest.logic.Progress;

/**
 * Turn-in and rewards (docs 20 6.4 accept · complete). Order:
 *
 * <ol>
 *   <li>Main: check READY, recheck and remove deliver items, COMPLETING
 *   <li>Virtual thread (one transaction): unique-ID registry state change + quest_progress
 *       COMPLETED (paid = [grant])
 *   <li>Currency: CurrencyService.give(QUEST_REWARD, reference ID) — the ledger unique key blocks
 *       duplicates
 *   <li>Main: experience, affinity, hints and items (grant), reputation, flags, titles and
 *       chronicle (idempotent), then done in paid when all are finished
 *   <li>QuestCompletedEvent, sound, cutscene_at: complete cutscene
 * </ol>
 *
 * If step 2 fails, the items are returned and the quest goes back to READY. If the server stops
 * between steps 3 and 4, only the idempotent rewards of completed rows without done are given again
 * on the next join. The grant bundle is recorded in the completion transaction because "recorded
 * but not given" (admin recovery) is safer than "given but not recorded" (duplication).
 */
public final class RewardPayer {

    private final QuestEngine engine;
    private final Map<String, CompletableFuture<Boolean>> completing = new HashMap<>();

    private final RewardGrants grants;
    private final ItemDelivery delivery;
    private final CompletionEffects effects;
    private final DeliverCheck deliverCheck;

    RewardPayer(QuestEngine engine) {
        this.engine = engine;
        this.grants = new RewardGrants(engine);
        this.delivery = new ItemDelivery(engine);
        this.effects = new CompletionEffects(engine);
        this.deliverCheck = new DeliverCheck(engine);
    }

    ItemDelivery delivery() {
        return delivery;
    }

    CompletionEffects effects() {
        return effects;
    }

    private static String key(UUID uuid, QuestId quest) {
        return uuid + "|" + quest;
    }

    public boolean completing(UUID uuid, QuestId quest) {
        return completing.containsKey(key(uuid, quest));
    }

    /**
     * Server move during a turn-in: waits for the completion transaction in progress (Core
     * HandoffBlocker delay).
     */
    HandoffBlocker.Verdict handoff(Player player) {
        String prefix = player.getUniqueId() + "|";
        List<CompletableFuture<Boolean>> pending = new ArrayList<>();
        completing.forEach(
                (k, f) -> {
                    if (k.startsWith(prefix)) {
                        pending.add(f);
                    }
                });
        if (pending.isEmpty()) {
            return HandoffBlocker.ALLOW;
        }
        return new HandoffBlocker.Delay(
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)));
    }

    // ------------------------------------------------------------------ Reward snapshot

    /**
     * Fixes currency and reputation at accept time (request gold depends on the highest unlocked
     * region and is fixed to the value at accept time).
     */
    Progress.Snapshot snapshot(HarborPlayer hp, QuestDef def) {
        Rewards r = def.rewards();
        if (!def.type().board()) {
            return new Progress.Snapshot(
                    Math.max(0, r.gold()),
                    Math.max(0, r.mulbineul()),
                    Math.max(0, r.reputation()),
                    Math.max(0, r.exp()),
                    null);
        }
        BoardRules rules = engine.boardRules();
        BoardRules.Slot slot = rules.slot(def.type());
        Optional<RegionId> highest = engine.highestRegion(hp.uuid());
        long tableGold =
                rules.gold(highest.orElse(null))
                        .map(
                                g ->
                                        switch (def.type()) {
                                            case DAILY -> g.daily();
                                            case WEEKLY -> g.weekly();
                                            default -> 0L;
                                        })
                        .orElse(0L);
        return new Progress.Snapshot(
                r.gold() >= 0 ? r.gold() : tableGold,
                r.mulbineul() >= 0 ? r.mulbineul() : slot.mulbineul(),
                r.reputation() >= 0 ? r.reputation() : slot.reputation(),
                Math.max(0, r.exp()),
                highest.map(RegionId::toString).orElse(null));
    }

    static ReputationSource source(QuestType type) {
        return switch (type) {
            case MAIN -> ReputationSource.MAIN_STORY;
            case REGION -> ReputationSource.REGION_QUEST;
            case DAILY -> ReputationSource.DAILY_REQUEST;
            case WEEKLY -> ReputationSource.WEEKLY_REQUEST;
            case COLLECT -> ReputationSource.COLLECT_REQUEST;
            default -> ReputationSource.OTHER;
        };
    }

    // ------------------------------------------------------------------ Turn-in

    CompletableFuture<Boolean> turnIn(Player player, QuestId quest) {
        HarborPlayer hp = engine.api.players().get(player);
        Optional<QuestDef> found = engine.def(quest);
        QuestEntry entry = hp.get(QuestEngine.COMPONENT).entries().get(quest);
        if (found.isEmpty() || entry == null) {
            engine.api.messages().send(player, "quest.error.not_active");
            return CompletableFuture.completedFuture(false);
        }
        QuestDef def = found.get();
        if (completing(hp.uuid(), quest)) {
            return CompletableFuture.completedFuture(false);
        }
        if (entry.state() != QuestState.READY) {
            engine.api.messages().send(player, "quest.error.not_ready");
            return CompletableFuture.completedFuture(false);
        }
        DeliverPlan.Result plan = deliverCheck.plan(player, def);
        if (!plan.ok()) {
            deliverCheck.explainMissing(player, def, plan);
            return CompletableFuture.completedFuture(false);
        }
        int advance = def.rewards().jobAdvance();
        // If the player already has that tier, complete right away instead of requesting the
        // advancement again: if the delivered items were removed while the advancement confirmation
        // was open, only the advancement happened and the quest stayed READY, and every later
        // request
        // failed because the tier was already owned, so the quest could never be finished
        if (advance > 0 && hp.jobTier().level() < advance) {
            // job advancement quests: Jobs handles the confirmation Dialog, branch choice, gold
            // deduction and advancement cutscene, and the quest completes on JobAdvancedEvent
            return engine.deps
                    .jobs()
                    .requestAdvance(player, JobTier.of(advance))
                    .thenApplyAsync(
                            result -> {
                                if (result != AdvanceResult.OK) {
                                    engine.api
                                            .messages()
                                            .send(
                                                    player,
                                                    "quest.job.advance_"
                                                            + result.name()
                                                                    .toLowerCase(
                                                                            java.util.Locale.ROOT));
                                }
                                return result == AdvanceResult.OK;
                            },
                            engine.tasks.main());
        }
        if (plan.precious()) {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            engine.api
                    .dialogs()
                    .confirm(
                            player,
                            engine.api.messages().get("quest.deliver.confirm_title"),
                            List.of(
                                    engine.api
                                            .messages()
                                            .get(
                                                    "quest.deliver.confirm_body",
                                                    Placeholder.unparsed("name", def.name()))),
                            engine.api.messages().get("quest.deliver.yes"),
                            engine.api.messages().get("quest.deliver.no"),
                            ok -> {
                                if (ok) {
                                    var _ =
                                            complete(player, def, true)
                                                    .whenComplete(
                                                            (r, e) ->
                                                                    result.complete(
                                                                            Boolean.TRUE.equals(
                                                                                    r)));
                                } else {
                                    result.complete(false);
                                }
                            });
            return result;
        }
        return complete(player, def, true);
    }

    /**
     * After the advancement is committed (JobAdvancedEvent): completes the READY advancement quest
     * of that tier ('7. Balance Design' ch. 7 mulbineul 5 is a YAML reward).
     */
    void onJobAdvanced(Player player, int tier) {
        HarborPlayer hp = engine.api.players().get(player);
        for (QuestEntry e : hp.get(QuestEngine.COMPONENT).entries().values()) {
            if (e.state() != QuestState.READY) {
                continue;
            }
            engine.def(e.quest())
                    .filter(d -> d.rewards().jobAdvance() == tier)
                    .ifPresent(
                            d -> {
                                var _ = complete(player, d, true);
                            });
        }
    }

    /**
     * Completion (steps 1~5).
     *
     * @param reward if false, completes without rewards (admin command --no-reward)
     */
    CompletableFuture<Boolean> complete(Player player, QuestDef def, boolean reward) {
        HarborPlayer hp = engine.api.players().get(player);
        UUID uuid = hp.uuid();
        QuestId quest = def.id();
        QuestEntry entry = hp.get(QuestEngine.COMPONENT).entries().get(quest);
        if (entry == null || !entry.state().open() || completing(uuid, quest)) {
            return CompletableFuture.completedFuture(false);
        }
        // step 1: recheck and remove deliver items
        DeliverPlan.Result plan = deliverCheck.plan(player, def);
        if (!plan.ok() && reward) {
            deliverCheck.explainMissing(player, def, plan);
            return CompletableFuture.completedFuture(false);
        }
        List<ItemStack> removed = new ArrayList<>();
        List<UUID> uniques = new ArrayList<>();
        if (plan.ok()) {
            PlayerInventory inv = player.getInventory();
            for (Map.Entry<Integer, Integer> take : plan.take().entrySet()) {
                ItemStack stack = inv.getItem(take.getKey());
                if (stack == null) {
                    continue;
                }
                ItemStack copy = stack.clone();
                copy.setAmount(take.getValue());
                removed.add(copy);
                engine.api
                        .items()
                        .read(stack)
                        .ifPresent(
                                item -> {
                                    if (item instanceof HarborItem.Fish fish
                                            && fish.uid() != null) {
                                        uniques.add(fish.uid());
                                    }
                                });
                int left = stack.getAmount() - take.getValue();
                if (left <= 0) {
                    inv.setItem(take.getKey(), null);
                } else {
                    stack.setAmount(left);
                }
            }
        }
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        completing.put(key(uuid, quest), done);
        // Hold the session (quit save / server move) until the completion and any grant undo are
        // committed. Otherwise a player who quits mid-completion can load on another server before
        // the undo lands, the new server sees the grant as paid, and the grant bundle is lost.
        CompletableFuture<Void> sessionWrites = new CompletableFuture<>();
        engine.api.players().holdSession(uuid, sessionWrites);
        engine.invalidate(uuid);
        engine.cancelPending(uuid, quest);
        Progress finished =
                reward
                        ? entry.progress().withPaid(Progress.PAID_GRANT)
                        : entry.progress()
                                .withPaid(Progress.PAID_GRANT)
                                .withPaid(Progress.PAID_DONE);
        int rowSeason = entry.season();
        FishLedger.State next =
                def.donateDelivered() ? FishLedger.State.DONATED : FishLedger.State.DESTROYED;
        // step 2: one transaction (unique-ID items are accepted only while their registry state is
        // ACTIVE: blocks copied items)
        var _ =
                engine.serial(
                                uuid,
                                c -> {
                                    for (UUID uid : uniques) {
                                        if (!FishLedger.transition(
                                                c, uid, FishLedger.State.ACTIVE, next)) {
                                            throw new java.sql.SQLException(
                                                    "장부 상태가 ACTIVE가 아닌 고유 ID 아이템: " + uid);
                                        }
                                    }
                                    if (!QuestRepository.complete(
                                            c, uuid, rowSeason, quest, finished)) {
                                        throw new java.sql.SQLException("이미 완료되었거나 진행 중이 아닌 퀘스트");
                                    }
                                    return Boolean.TRUE;
                                })
                        .whenComplete(
                                (ok, error) -> {
                                    completing.remove(key(uuid, quest));
                                    engine.invalidate(uuid);
                                    if (error != null) {
                                        engine.log.warn(
                                                "퀘스트 완료 실패 {} {}: {}",
                                                uuid,
                                                quest,
                                                String.valueOf(error.getMessage()));
                                        delivery.giveBack(uuid, removed);
                                        if (player.isOnline()) {
                                            engine.api
                                                    .messages()
                                                    .send(player, "quest.error.complete_failed");
                                        }
                                        sessionWrites.complete(null);
                                        done.complete(false);
                                        return;
                                    }
                                    QuestEntry completed =
                                            new QuestEntry(
                                                    quest,
                                                    rowSeason,
                                                    QuestState.COMPLETED,
                                                    finished);
                                    var _ = engine.setCompletionFlag(uuid, def);
                                    Optional<HarborPlayer> online = engine.api.players().get(uuid);
                                    online.ifPresent(
                                            p ->
                                                    engine.put(
                                                            p,
                                                            p.get(QuestEngine.COMPONENT)
                                                                    .with(completed)));
                                    Bukkit.getPluginManager()
                                            .callEvent(
                                                    new QuestStateChangedEvent(
                                                            uuid,
                                                            quest,
                                                            entry.state(),
                                                            QuestState.COMPLETED));
                                    if (reward) {
                                        var _ =
                                                grants.pay(uuid, def, completed, true)
                                                        .whenComplete(
                                                                (v, e) ->
                                                                        sessionWrites.complete(
                                                                                null));
                                    } else {
                                        sessionWrites.complete(null);
                                        effects.afterComplete(
                                                uuid,
                                                def,
                                                completed,
                                                effects.summary(def, finished.snap(), List.of()));
                                    }
                                    // the completed row and registry are committed: also save soon
                                    // the inventory that lost the delivered items and received
                                    // rewards. If the server dies,
                                    // the delivered items come back while the completion remains,
                                    // causing duplication (a currency-only reward is already saved,
                                    // but item and experience rewards
                                    // are not)
                                    engine.api.players().saveSoon(uuid);
                                    done.complete(true);
                                });
        return done;
    }

    /** On the next join: gives the remaining rewards of completed rows without done. */
    void recover(Player player) {
        HarborPlayer hp = engine.api.players().get(player);
        for (QuestEntry e : hp.get(QuestEngine.COMPONENT).entries().values()) {
            if (e.state() == QuestState.COMPLETED && !e.progress().paid(Progress.PAID_DONE)) {
                engine.def(e.quest())
                        .ifPresent(
                                d -> {
                                    engine.log.info("미지급 보상 복구: {} {}", player.getName(), d.id());
                                    var _ = grants.pay(hp.uuid(), d, e, false);
                                });
            }
        }
    }

    // ------------------------------------------------------------------ deliver

    DeliverPlan.Result plan(Player player, QuestDef def) {
        return deliverCheck.plan(player, def);
    }
}
