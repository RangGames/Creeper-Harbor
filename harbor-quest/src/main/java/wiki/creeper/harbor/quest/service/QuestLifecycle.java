package wiki.creeper.harbor.quest.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.api.event.QuestAbandonedEvent;
import wiki.creeper.harbor.quest.api.event.QuestAcceptedEvent;
import wiki.creeper.harbor.quest.api.event.QuestStateChangedEvent;
import wiki.creeper.harbor.quest.content.CutsceneAt;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.data.QuestData;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.QuestMachine;

/**
 * 수락·포기·추적 (docs 20 6.4 상태 기계). 행 쓰기는 {@link QuestEngine#serial}로 플레이어별 순서를 지키고, 결과는 메인 스레드에서 반영한다.
 */
final class QuestLifecycle {

    private final QuestEngine engine;

    QuestLifecycle(QuestEngine engine) {
        this.engine = engine;
    }

    CompletableFuture<Boolean> accept(Player player, QuestId quest) {
        HarborPlayer hp = engine.api.players().get(player);
        Optional<QuestDef> found = engine.def(quest);
        if (found.isEmpty()) {
            engine.api.messages().send(player, "quest.error.unknown");
            return CompletableFuture.completedFuture(false);
        }
        QuestDef def = found.get();
        UUID uuid = hp.uuid();
        QuestEngine.Avail avail = engine.avail(hp, def);
        if (avail.state() != QuestState.AVAILABLE) {
            explainLocked(player, def, avail);
            return CompletableFuture.completedFuture(false);
        }
        if (!def.type().board()) {
            long open = engine.open(hp).stream().filter(e -> !e.getKey().type().board()).count();
            int limit = engine.balance().integer("quest.active_limit", 20);
            if (open >= limit) {
                engine.api
                        .messages()
                        .send(
                                player,
                                "quest.error.active_limit",
                                Placeholder.unparsed("limit", String.valueOf(limit)));
                return CompletableFuture.completedFuture(false);
            }
        } else if (!engine.board().canChoose(hp, def)) {
            engine.api.messages().send(player, "quest.board.no_pick");
            return CompletableFuture.completedFuture(false);
        }
        if (!engine.lock(uuid)) {
            engine.api.messages().send(player, "quest.error.busy");
            return CompletableFuture.completedFuture(false);
        }
        Progress.Snapshot snap = engine.payer().snapshot(hp, def);
        String cycle = engine.cycleOf(def);
        Progress progress = Progress.start(def.hash(), cycle, snap, System.currentTimeMillis());
        int rowSeason = engine.season();
        engine.cancelPending(uuid, quest);
        var boardWrite = def.type().board() ? engine.board().chooseWrite(hp, def) : null;
        return engine.serial(
                        uuid,
                        c -> {
                            QuestRepository.AcceptResult r =
                                    QuestRepository.accept(c, uuid, rowSeason, quest, progress);
                            if (r == QuestRepository.AcceptResult.OK && boardWrite != null) {
                                boardWrite.accept(c);
                            }
                            return r;
                        })
                .handle(
                        (result, error) -> {
                            engine.unlock(uuid);
                            if (error != null) {
                                engine.log.error("퀘스트 수락 저장 실패: {} {}", uuid, quest, error);
                                engine.api.messages().send(player, "quest.error.save");
                                return false;
                            }
                            if (result != QuestRepository.AcceptResult.OK) {
                                engine.api.messages().send(player, "quest.error.already");
                                return false;
                            }
                            afterAccept(
                                    player,
                                    hp,
                                    def,
                                    new QuestEntry(quest, rowSeason, QuestState.ACTIVE, progress));
                            return true;
                        });
    }

    private void afterAccept(Player player, HarborPlayer hp, QuestDef def, QuestEntry entry) {
        QuestData data = hp.get(QuestEngine.COMPONENT).with(entry);
        if (def.type().board()) {
            data = engine.board().markChosen(data, def);
        }
        if (data.tracked() == null || def.main()) {
            data = data.withTracked(def.id());
        }
        engine.put(hp, data);
        Bukkit.getPluginManager()
                .callEvent(
                        new QuestStateChangedEvent(
                                hp.uuid(), def.id(), QuestState.AVAILABLE, QuestState.ACTIVE));
        Bukkit.getPluginManager()
                .callEvent(new QuestAcceptedEvent(player, def.id(), def.type().key()));
        engine.notifier().accepted(player, def);
        engine.refreshGauges(player, def.id());
        engine.checkReady(player, def);
        HarborId cutscene = def.cutscene();
        if (cutscene != null && def.cutsceneAt().kind() == CutsceneAt.Kind.ACCEPT) {
            engine.playCutscene(player, cutscene);
        }
        engine.startCutsceneObjective(player, def);
        engine.refreshMarkers(hp.uuid());
    }

    /** 잠긴 이유 안내 (주차 잠금이면 "N주차에 열립니다 (D-n)"). */
    void explainLocked(Player player, QuestDef def, QuestEngine.Avail avail) {
        var messages = engine.api.messages();
        switch (avail.state()) {
            case ACTIVE, READY, COMPLETING -> messages.send(player, "quest.error.already");
            case COMPLETED -> messages.send(player, "quest.error.completed");
            case FAILED -> messages.send(player, "quest.error.failed");
            default -> {
                if (avail.lockedWeek() > 0) {
                    messages.send(
                            player,
                            "quest.locked.week",
                            Placeholder.unparsed("week", String.valueOf(avail.lockedWeek())),
                            Placeholder.unparsed(
                                    "days", String.valueOf(daysUntilWeek(avail.lockedWeek()))));
                } else {
                    Optional<Instant> cooldown = engine.abandonCooldown(player.getUniqueId(), def);
                    if (cooldown.isPresent()) {
                        long minutes =
                                Math.max(
                                        1,
                                        Duration.between(Instant.now(), cooldown.get()).toMinutes()
                                                + 1);
                        messages.send(
                                player,
                                "quest.locked.cooldown",
                                Placeholder.unparsed("minutes", String.valueOf(minutes)));
                    } else {
                        messages.send(player, "quest.locked.requires");
                    }
                }
            }
        }
    }

    /** 그 주차가 시작할 때까지 남은 날 (D-n). */
    long daysUntilWeek(int week) {
        long days =
                Duration.between(engine.api.time().now(), engine.deps.clock().weekStart(week))
                        .toDays();
        return Math.max(0, days + 1);
    }

    CompletableFuture<Boolean> abandon(Player player, QuestId quest) {
        HarborPlayer hp = engine.api.players().get(player);
        Optional<QuestDef> def = engine.def(quest);
        QuestEntry entry = hp.get(QuestEngine.COMPONENT).entries().get(quest);
        if (def.isEmpty() || entry == null) {
            engine.api.messages().send(player, "quest.error.not_active");
            return CompletableFuture.completedFuture(false);
        }
        if (!QuestMachine.canAbandon(def.get(), engine.avail(hp, def.get()).state())) {
            engine.api.messages().send(player, "quest.error.cannot_abandon");
            return CompletableFuture.completedFuture(false);
        }
        UUID uuid = hp.uuid();
        if (!engine.lock(uuid)) {
            engine.api.messages().send(player, "quest.error.busy");
            return CompletableFuture.completedFuture(false);
        }
        engine.cancelPending(uuid, quest);
        int rowSeason = entry.season();
        return engine.serial(
                        uuid,
                        c -> {
                            QuestRepository.delete(c, uuid, rowSeason, quest);
                            return Boolean.TRUE;
                        })
                .handle(
                        (ok, error) -> {
                            engine.unlock(uuid);
                            if (error != null) {
                                engine.log.error("퀘스트 포기 저장 실패: {} {}", uuid, quest, error);
                                engine.api.messages().send(player, "quest.error.save");
                                return false;
                            }
                            engine.availability()
                                    .startAbandonCooldown(uuid, quest, entry.progress().cycle());
                            engine.put(hp, hp.get(QuestEngine.COMPONENT).without(quest));
                            Bukkit.getPluginManager()
                                    .callEvent(
                                            new QuestStateChangedEvent(
                                                    uuid,
                                                    quest,
                                                    entry.state(),
                                                    QuestState.ABANDONED));
                            Bukkit.getPluginManager()
                                    .callEvent(new QuestAbandonedEvent(player, quest));
                            engine.notifier().abandoned(player, def.get());
                            engine.refreshMarkers(uuid);
                            return true;
                        });
    }

    boolean track(Player player, QuestId quest) {
        HarborPlayer hp = engine.api.players().get(player);
        QuestEntry e = hp.get(QuestEngine.COMPONENT).entries().get(quest);
        if (e == null || !e.state().open()) {
            return false;
        }
        hp.set(QuestEngine.COMPONENT, hp.get(QuestEngine.COMPONENT).withTracked(quest));
        engine.def(quest).ifPresent(d -> engine.notifier().tracking(player, d));
        return true;
    }
}
