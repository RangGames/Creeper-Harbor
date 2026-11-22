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
 * 보고·보상 (docs 20 6.4 수락 · 완료). 순서:
 *
 * <ol>
 *   <li>메인: READY 확인, deliver 아이템 재검사·제거, COMPLETING
 *   <li>가상 스레드 (한 트랜잭션): 고유 ID 장부 상태 전환 + quest_progress COMPLETED (paid = [grant])
 *   <li>재화: CurrencyService.give(QUEST_REWARD, 참조 ID) — 원장 유니크로 중복 차단
 *   <li>메인: 경험치·호감도·힌트·아이템 (grant), 평판·플래그·칭호·연대기 (멱등), 다 끝나면 paid에 done
 *   <li>QuestCompletedEvent, 효과음, cutscene_at: complete 컷씬
 * </ol>
 *
 * 2단계가 실패하면 아이템을 돌려주고 READY로 되돌린다. 3~4단계 사이에 서버가 꺼지면 다음 접속 때 done이 없는 완료 행의 멱등 보상만 다시 준다. grant 묶음은
 * '줬는데 기록 못 함'(복제)보다 '기록했는데 못 줌'(관리자 복구)이 안전하므로 완료 트랜잭션에 함께 기록한다.
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

    /** 보고 중 서버 이동: 진행 중인 완료 트랜잭션을 기다린다 (Core HandoffBlocker 지연). */
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

    // ------------------------------------------------------------------ 보상 스냅샷

    /** 수락할 때 재화·평판을 정해 둔다 (의뢰 골드는 해금한 가장 높은 지역 기준, 수락 시점 값으로 고정). */
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

    // ------------------------------------------------------------------ 보고

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
        // 이미 그 등급이면 전직을 다시 요청하지 않고 바로 완료한다: 전직 확인 창을 띄운 사이 납품 아이템을 치우면 전직만 되고 퀘스트는 READY로
        // 남는데, 그 뒤 요청은 이미 가진 등급이라 매번 실패해 퀘스트를 영영 끝낼 수 없었다
        if (advance > 0 && hp.jobTier().level() < advance) {
            // 전직 퀘스트: Jobs가 확인 Dialog·계열 선택·골드 차감·전직 컷씬을 처리하고, JobAdvancedEvent에서 완료한다
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

    /** 전직이 커밋된 뒤 (JobAdvancedEvent): READY인 그 단계 전직 퀘스트를 완료한다 ('7. 밸런스 기획' 7장 물비늘 5는 YAML 보상). */
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
     * 완료 처리 (1~5단계).
     *
     * @param reward false면 보상 없이 완료 (관리 명령 --no-reward)
     */
    CompletableFuture<Boolean> complete(Player player, QuestDef def, boolean reward) {
        HarborPlayer hp = engine.api.players().get(player);
        UUID uuid = hp.uuid();
        QuestId quest = def.id();
        QuestEntry entry = hp.get(QuestEngine.COMPONENT).entries().get(quest);
        if (entry == null || !entry.state().open() || completing(uuid, quest)) {
            return CompletableFuture.completedFuture(false);
        }
        // 1단계: deliver 재검사·제거
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
        // 2단계: 한 트랜잭션 (고유 ID 아이템은 장부 상태가 ACTIVE일 때만 받는다: 복사 아이템 차단)
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
                                        grants.pay(uuid, def, completed, true);
                                    } else {
                                        effects.afterComplete(
                                                uuid,
                                                def,
                                                completed,
                                                effects.summary(def, finished.snap(), List.of()));
                                    }
                                    // 완료 행·장부는 커밋됐다: 납품으로 빠진 아이템과 보상을 받은 인벤토리도 곧 저장한다. 서버가 죽으면
                                    // 납품 아이템이 돌아오고 완료는 남아 복제된다 (재화 보상만 있으면 이미 저장되지만 아이템·경험치 보상은
                                    // 아니다)
                                    engine.api.players().saveSoon(uuid);
                                    done.complete(true);
                                });
        return done;
    }

    /** 다음 접속 때: done이 없는 완료 행의 보상을 마저 준다. */
    void recover(Player player) {
        HarborPlayer hp = engine.api.players().get(player);
        for (QuestEntry e : hp.get(QuestEngine.COMPONENT).entries().values()) {
            if (e.state() == QuestState.COMPLETED && !e.progress().paid(Progress.PAID_DONE)) {
                engine.def(e.quest())
                        .ifPresent(
                                d -> {
                                    engine.log.info("미지급 보상 복구: {} {}", player.getName(), d.id());
                                    grants.pay(hp.uuid(), d, e, false);
                                });
            }
        }
    }

    // ------------------------------------------------------------------ deliver

    DeliverPlan.Result plan(Player player, QuestDef def) {
        return deliverCheck.plan(player, def);
    }
}
