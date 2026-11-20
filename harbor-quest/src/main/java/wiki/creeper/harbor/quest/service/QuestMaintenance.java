package wiki.creeper.harbor.quest.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.api.event.QuestStateChangedEvent;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.data.QuestData;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.data.QuestRepository;
import wiki.creeper.harbor.quest.logic.Progress;
import wiki.creeper.harbor.quest.logic.QuestMachine;

/** 진행 이전(리로드·접속), 기간 만료 실패, 관리 명령의 상태·진행 수정. 관리 명령의 admin_log 기록은 호출하는 명령·GUI가 한다. 메인 스레드 전용. */
final class QuestMaintenance {

    private final QuestEngine engine;

    QuestMaintenance(QuestEngine engine) {
        this.engine = engine;
    }

    /** 리로드·접속 때 진행 이전 (목표 key 기준). */
    QuestEngine.MigrationReport migrate(HarborPlayer hp) {
        QuestData data = hp.get(QuestEngine.COMPONENT);
        int changed = 0;
        int orphaned = 0;
        Map<QuestId, QuestEntry> next = new LinkedHashMap<>(data.entries());
        for (QuestEntry e : data.entries().values()) {
            if (!e.state().open()) {
                continue;
            }
            Optional<QuestDef> def = engine.def(e.quest());
            if (def.isEmpty()) {
                orphaned++;
                continue;
            }
            if (e.progress().version() == def.get().hash()) {
                continue;
            }
            Progress moved = QuestMachine.migrate(def.get(), e.progress());
            QuestState state =
                    QuestMachine.afterProgress(e.state(), QuestMachine.ready(def.get(), moved));
            QuestEntry updated = new QuestEntry(e.quest(), e.season(), state, moved);
            next.put(e.quest(), updated);
            engine.saveLater(hp.uuid(), updated);
            changed++;
        }
        if (changed > 0) {
            engine.put(hp, new QuestData(next, data.boards(), data.tracked()));
        }
        return new QuestEngine.MigrationReport(changed, orphaned);
    }

    /** 1분마다: 기간이 끝난 이벤트 퀘스트를 FAILED로 (docs 20 6.4 상태 기계). */
    void failExpiredEvents() {
        for (HarborPlayer hp : engine.api.players().online()) {
            for (Map.Entry<QuestDef, QuestEntry> e : engine.open(hp)) {
                QuestDef def = e.getKey();
                QuestDef.TimeWindow window = def.timeWindow();
                if (window == null
                        || window.end() == null
                        || engine.inWindow(def)
                        || engine.payer().completing(hp.uuid(), def.id())) {
                    continue;
                }
                var _ = setStateNow(hp, e.getValue(), QuestState.FAILED);
                Player player = Bukkit.getPlayer(hp.uuid());
                if (player != null) {
                    engine.notifier().failed(player, def);
                }
            }
        }
    }

    /** 상태를 즉시 바꾼다 (실패·관리 명령). */
    CompletableFuture<Boolean> setStateNow(HarborPlayer hp, QuestEntry entry, QuestState state) {
        UUID uuid = hp.uuid();
        QuestId quest = entry.quest();
        engine.cancelPending(uuid, quest);
        QuestEntry next = entry.withState(state);
        engine.put(hp, hp.get(QuestEngine.COMPONENT).with(next));
        Bukkit.getPluginManager()
                .callEvent(new QuestStateChangedEvent(uuid, quest, entry.state(), state));
        engine.refreshMarkers(uuid);
        return engine.serial(
                uuid,
                c -> {
                    QuestRepository.upsert(c, uuid, entry.season(), quest, state, entry.progress());
                    return true;
                });
    }

    /** 관리 명령: 상태 강제 (active · ready · completed · failed). 행이 없으면 지금 시즌 행을 만든다. */
    CompletableFuture<Boolean> adminSet(Player player, QuestDef def, QuestState state) {
        HarborPlayer hp = engine.api.players().get(player);
        QuestEntry entry =
                hp.get(QuestEngine.COMPONENT)
                        .entry(def.id())
                        .orElseGet(
                                () ->
                                        new QuestEntry(
                                                def.id(),
                                                engine.season(),
                                                QuestState.ACTIVE,
                                                Progress.start(
                                                        def.hash(),
                                                        engine.cycleOf(def),
                                                        engine.payer().snapshot(hp, def),
                                                        System.currentTimeMillis())));
        Progress progress = entry.progress();
        if (state == QuestState.COMPLETED) {
            progress = progress.withPaid(Progress.PAID_GRANT).withPaid(Progress.PAID_DONE);
            var _ = setCompletionFlag(hp.uuid(), def);
        }
        return setStateNow(hp, entry.withProgress(progress), state);
    }

    /**
     * 완료 플래그 {@code quest/<유형>/<이름>} (퀘스트 ID 경로 그대로). Economy 상점·레시피 해금과 World 진입 조건이 퀘스트 완료를 이
     * 플래그로 읽는다 (Quest에 의존하지 않게). 메인은 영구, 나머지는 시즌 범위.
     */
    CompletableFuture<Void> setCompletionFlag(UUID uuid, QuestDef def) {
        return engine.api
                .flags()
                .setPlayer(
                        uuid,
                        def.main() ? FlagService.Scope.PERM : FlagService.Scope.SEASON,
                        def.id().id().path());
    }

    /** 관리 명령: 목표 진행 값 수정. */
    boolean adminProgress(Player player, QuestDef def, String key, int value) {
        HarborPlayer hp = engine.api.players().get(player);
        QuestEntry entry = hp.get(QuestEngine.COMPONENT).entries().get(def.id());
        if (entry == null || !entry.state().open() || def.objective(key).isEmpty()) {
            return false;
        }
        Progress progress = QuestMachine.force(def, entry.progress(), key, value);
        QuestState state =
                QuestMachine.afterProgress(entry.state(), QuestMachine.ready(def, progress));
        QuestEntry next = new QuestEntry(def.id(), entry.season(), state, progress);
        engine.put(hp, hp.get(QuestEngine.COMPONENT).with(next));
        engine.saveLater(hp.uuid(), next);
        if (state == QuestState.READY && entry.state() != QuestState.READY) {
            engine.progressor().onReady(player, def);
        }
        engine.refreshMarkers(hp.uuid());
        return true;
    }

    /** 관리 명령: 행 삭제 (메인도 가능). */
    CompletableFuture<Boolean> adminReset(Player player, QuestId quest) {
        HarborPlayer hp = engine.api.players().get(player);
        QuestEntry entry = hp.get(QuestEngine.COMPONENT).entries().get(quest);
        if (entry == null) {
            return CompletableFuture.completedFuture(false);
        }
        UUID uuid = hp.uuid();
        engine.cancelPending(uuid, quest);
        engine.put(hp, hp.get(QuestEngine.COMPONENT).without(quest));
        engine.refreshMarkers(uuid);
        return engine.serial(
                uuid,
                c -> {
                    QuestRepository.delete(c, uuid, entry.season(), quest);
                    return true;
                });
    }
}
