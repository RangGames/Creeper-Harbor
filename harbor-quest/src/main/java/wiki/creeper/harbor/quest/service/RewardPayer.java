package wiki.creeper.harbor.quest.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.jobs.api.AdvanceResult;
import wiki.creeper.harbor.jobs.api.ExpGrant;
import wiki.creeper.harbor.jobs.api.ExpSource;
import wiki.creeper.harbor.npc.api.AffinitySource;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.quest.api.QuestRewardSummary;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.api.event.ChapterCompletedEvent;
import wiki.creeper.harbor.quest.api.event.QuestCompletedEvent;
import wiki.creeper.harbor.quest.api.event.QuestStateChangedEvent;
import wiki.creeper.harbor.quest.content.BoardRules;
import wiki.creeper.harbor.quest.content.ChronicleDef;
import wiki.creeper.harbor.quest.content.CutsceneAt;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.QuestType;
import wiki.creeper.harbor.quest.content.Rewards;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.CatchFacts;
import wiki.creeper.harbor.quest.logic.DeliverPlan;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.RefIds;
import wiki.creeper.harbor.season.api.TitleId;

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

    RewardPayer(QuestEngine engine) {
        this.engine = engine;
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
        DeliverPlan.Result plan = plan(player, def);
        if (!plan.ok()) {
            explainMissing(player, def, plan);
            return CompletableFuture.completedFuture(false);
        }
        int advance = def.rewards().jobAdvance();
        if (advance > 0) {
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
        DeliverPlan.Result plan = plan(player, def);
        if (!plan.ok() && reward) {
            explainMissing(player, def, plan);
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
                                        giveBack(uuid, removed);
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
                                        pay(uuid, def, completed, true);
                                    } else {
                                        afterComplete(
                                                uuid,
                                                def,
                                                completed,
                                                summary(def, finished.snap(), List.of()));
                                    }
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
                                    pay(hp.uuid(), d, e, false);
                                });
            }
        }
    }

    /**
     * 3~4단계.
     *
     * @param fresh 방금 완료한 것 (grant 묶음을 준다). 복구면 grant가 기록돼 있지 않을 때만 준다
     */
    private void pay(UUID uuid, QuestDef def, QuestEntry entry, boolean fresh) {
        Progress progress = entry.progress();
        Progress.Snapshot snap = progress.snap();
        Rewards rewards = def.rewards();
        String ref = RefIds.reward(def.id(), entry.season(), progress.cycle());
        List<CompletableFuture<Boolean>> idempotent = new ArrayList<>();
        // 3단계: 재화 (원장 참조 ID 유니크)
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
        // 4단계: 멱등 보상 (평판 refId, 플래그, 칭호, 연대기)
        if (snap.reputation() > 0) {
            idempotent.add(
                    engine.deps
                            .reputation()
                            .add(uuid, snap.reputation(), source(def.type()), ref)
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
        // grant 묶음: 경험치·호감도·힌트·아이템
        List<String> itemNames = new ArrayList<>();
        boolean grantNow = fresh || !progress.paid(Progress.PAID_GRANT);
        CompletableFuture<Boolean> grantDone;
        if (!grantNow) {
            grantDone = CompletableFuture.completedFuture(true);
        } else if (player == null) {
            // 완료 트랜잭션 뒤 퇴장: grant 기록을 지워 다음 접속 때 준다
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
            // 복구: grant 기록을 먼저 커밋한 뒤 준다 (복제 방지)
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
            afterComplete(uuid, def, entry, summary(def, snap, itemNames));
        }
    }

    private static boolean isDuplicate(Result<Long, LedgerError> r) {
        return r instanceof Result.Err<Long, LedgerError> err
                && err.error() == LedgerError.DUPLICATE;
    }

    /** 경험치·호감도·힌트·아이템 (메인 스레드, 접속 중). */
    private void grant(
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
                    // 줄 힌트가 없으면 대체 골드 (docs 20 4.4 grantRandom 규칙, 제안값)
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
            deliverItem(player, stack, ref);
        }
    }

    /** 인벤토리가 차면 우편함 (Core api MailService, 구현 Economy). 우편함이 없으면 발밑에 떨어뜨린다. */
    private void deliverItem(Player player, ItemStack stack, String ref) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
        if (left.isEmpty()) {
            return;
        }
        for (ItemStack rest : left.values()) {
            sendMail(player.getUniqueId(), rest, ref, player.getLocation());
        }
        engine.api.messages().send(player, "quest.reward.mailed");
    }

    private void sendMail(UUID uuid, ItemStack stack, String ref, @Nullable Location fallback) {
        Optional<wiki.creeper.harbor.core.api.MailService> mail = engine.api.mail();
        if (mail.isPresent()) {
            var _ = mail.get().sendItem(uuid, stack, "quest", ref);
            return;
        }
        if (fallback != null && fallback.getWorld() != null) {
            fallback.getWorld().dropItemNaturally(fallback, stack);
        } else {
            engine.log.error("우편함이 없어 퀘스트 아이템을 줄 수 없습니다 (관리자 복구 필요): {} {}", uuid, stack);
        }
    }

    /** 실패한 보고에서 뺀 아이템 반환 (오프라인이면 우편함). */
    private void giveBack(UUID uuid, List<ItemStack> removed) {
        Player player = Bukkit.getPlayer(uuid);
        for (ItemStack stack : removed) {
            if (player != null) {
                player.getInventory()
                        .addItem(stack)
                        .values()
                        .forEach(
                                rest -> sendMail(uuid, rest, "quest_return", player.getLocation()));
            } else {
                sendMail(uuid, stack, "quest_return", null);
            }
        }
    }

    private QuestRewardSummary summary(QuestDef def, Progress.Snapshot snap, List<String> items) {
        return new QuestRewardSummary(
                snap.gold(),
                snap.mulbineul(),
                snap.exp(),
                snap.reputation(),
                items,
                def.rewards().title(),
                def.rewards().chronicle());
    }

    /** 5단계: 이벤트·효과음·컷씬·장 클리어·게시판·표시. */
    private void afterComplete(
            UUID uuid, QuestDef def, QuestEntry entry, QuestRewardSummary summary) {
        Bukkit.getPluginManager()
                .callEvent(
                        new QuestCompletedEvent(
                                uuid,
                                def.id(),
                                def.type().key(),
                                entry.progress().cycle(),
                                summary));
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            engine.api
                    .messages()
                    .send(
                            player,
                            "quest.completed",
                            Placeholder.unparsed("name", def.name()),
                            Placeholder.unparsed("money", String.valueOf(summary.gold())),
                            Placeholder.unparsed("mulbineul", String.valueOf(summary.mulbineul())),
                            Placeholder.unparsed("exp", String.valueOf(summary.exp())));
            HarborSounds.play(player, "harbor:ui.quest_complete"); // '15. 사운드' 4.5 의뢰 완료
            HarborId cutscene = def.cutscene();
            if (cutscene != null && def.cutsceneAt().kind() == CutsceneAt.Kind.COMPLETE) {
                engine.playCutscene(player, cutscene);
            }
            engine.refreshMarkers(uuid);
            engine.refreshGauges(player, null);
        }
        QuestEntry tracked = null;
        Optional<HarborPlayer> hp = engine.api.players().get(uuid);
        if (hp.isPresent() && def.id().equals(hp.get().get(QuestEngine.COMPONENT).tracked())) {
            // 추적 중이던 퀘스트가 끝나면 진행 중인 다른 퀘스트(메인 우선)로
            for (Map.Entry<QuestDef, QuestEntry> e : engine.open(hp.get())) {
                if (tracked == null || e.getKey().main()) {
                    tracked = e.getValue();
                }
            }
            hp.get()
                    .set(
                            QuestEngine.COMPONENT,
                            hp.get()
                                    .get(QuestEngine.COMPONENT)
                                    .withTracked(tracked == null ? null : tracked.quest()));
        }
        chapterCheck(uuid, def);
    }

    /** 장 마지막 퀘스트면 ChapterCompletedEvent, 서버 최초 클리어면 harbor:announce 공지. */
    private void chapterCheck(UUID uuid, QuestDef def) {
        if (!def.main()) {
            return;
        }
        ChronicleDef chronicle = engine.chronicleDef();
        Optional<ChronicleDef.Chapter> chapter =
                chronicle.chapters().stream()
                        .filter(c -> c.finalQuest().equals(def.id()))
                        .findFirst();
        if (chapter.isEmpty()) {
            return;
        }
        int number = chapter.get().chapter();
        Bukkit.getPluginManager().callEvent(new ChapterCompletedEvent(uuid, number, false));
        int season = engine.season();
        String name = Optional.ofNullable(Bukkit.getPlayer(uuid)).map(Player::getName).orElse("?");
        var _ =
                engine.api
                        .database()
                        .async(c -> QuestRepository.completedCount(c, def.id(), season))
                        .thenAcceptAsync(
                                count -> {
                                    if (count == 1) {
                                        Component msg =
                                                engine.api
                                                        .messages()
                                                        .get(
                                                                "quest.announce.first_chapter",
                                                                Placeholder.unparsed(
                                                                        "player", name),
                                                                Placeholder.unparsed(
                                                                        "chapter",
                                                                        chapter.get().name()));
                                        engine.api
                                                .bus()
                                                .publish(
                                                        Channels.ANNOUNCEMENTS,
                                                        new Channels.Announcement(
                                                                MiniMessage.miniMessage()
                                                                        .serialize(msg)));
                                    }
                                },
                                engine.tasks.main());
    }

    // ------------------------------------------------------------------ deliver

    DeliverPlan.Result plan(Player player, QuestDef def) {
        List<DeliverPlan.Slot> slots = new ArrayList<>();
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<HarborItem> item = engine.api.items().read(stack);
            if (item.isEmpty()) {
                continue;
            }
            CatchFacts facts;
            boolean precious;
            switch (item.get()) {
                case HarborItem.Fish fish -> {
                    Optional<FishDef> fd = engine.snapshot().get(FishDef.TYPE, fish.species().id());
                    if (fd.isEmpty()) {
                        continue;
                    }
                    facts =
                            CatchFacts.fish(
                                    fish.species(),
                                    fd.get().grade(),
                                    fd.get().region(),
                                    fish.quality().stars(),
                                    fish.sizeCm(),
                                    false);
                    precious = fish.quality().isMax() || fish.uid() != null;
                }
                case HarborItem.Plain plain -> {
                    facts = CatchFacts.item(plain.id());
                    precious = false;
                }
            }
            slots.add(new DeliverPlan.Slot(i, facts, stack.getAmount(), precious));
        }
        return DeliverPlan.plan(def.objectives(), slots);
    }

    private void explainMissing(Player player, QuestDef def, DeliverPlan.Result plan) {
        engine.api
                .messages()
                .send(
                        player,
                        "quest.deliver.missing_header",
                        Placeholder.unparsed("name", def.name()));
        plan.missing()
                .forEach(
                        (key, n) ->
                                def.objective(key)
                                        .ifPresent(
                                                o ->
                                                        engine.api
                                                                .messages()
                                                                .send(
                                                                        player,
                                                                        "quest.deliver.missing_line",
                                                                        Placeholder.unparsed(
                                                                                "objective",
                                                                                engine
                                                                                        .objectiveText(
                                                                                                player,
                                                                                                def,
                                                                                                o)),
                                                                        Placeholder.unparsed(
                                                                                "missing",
                                                                                String.valueOf(
                                                                                        n)))));
    }
}
