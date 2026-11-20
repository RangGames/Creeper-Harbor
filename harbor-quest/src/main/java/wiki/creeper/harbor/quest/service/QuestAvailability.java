package wiki.creeper.harbor.quest.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.content.Repeat;
import wiki.creeper.harbor.quest.data.QuestData;
import wiki.creeper.harbor.quest.data.QuestEntry;
import wiki.creeper.harbor.quest.logic.QuestMachine;
import wiki.creeper.harbor.quest.logic.Requirements;

/** 퀘스트 상태 계산과 캐시 (docs 20 6.4): 수락 가능 여부, 기간, 요구 조건, 포기 대기, 목표 역색인. 메인 스레드 전용. */
final class QuestAvailability {

    private final QuestEngine engine;
    private final Map<UUID, Map<QuestId, QuestEngine.Avail>> availCache = new HashMap<>();
    private final Map<UUID, Map<String, List<QuestId>>> index = new HashMap<>();
    private final Map<String, Instant> abandonCooldown = new HashMap<>();

    QuestAvailability(QuestEngine engine) {
        this.engine = engine;
    }

    void invalidate(UUID uuid) {
        availCache.remove(uuid);
        index.remove(uuid);
    }

    void invalidateAll() {
        availCache.clear();
        index.clear();
    }

    /** 계산 상태 (캐시). */
    QuestEngine.Avail avail(HarborPlayer hp, QuestDef def) {
        Map<QuestId, QuestEngine.Avail> cache =
                availCache.computeIfAbsent(hp.uuid(), u -> new HashMap<>());
        QuestEngine.Avail cached = cache.get(def.id());
        if (cached != null) {
            return cached;
        }
        QuestEngine.Avail computed = compute(hp, def);
        cache.put(def.id(), computed);
        return computed;
    }

    private QuestEngine.Avail compute(HarborPlayer hp, QuestDef def) {
        QuestData data = hp.get(QuestEngine.COMPONENT);
        QuestEntry entry = data.entries().get(def.id());
        if (entry != null) {
            QuestState s = entry.state();
            if (engine.payerAttached() && engine.payer().completing(hp.uuid(), def.id())) {
                return new QuestEngine.Avail(QuestState.COMPLETING, 0);
            }
            if (s == QuestState.ACTIVE || s == QuestState.READY) {
                return new QuestEngine.Avail(s, 0);
            }
            boolean sameCycle = entry.progress().cycle().equals(engine.cycleOf(def));
            if ((s == QuestState.COMPLETED || s == QuestState.FAILED)
                    && (def.repeat() == Repeat.NONE || sameCycle)) {
                return new QuestEngine.Avail(s, 0);
            }
        }
        if (!inWindow(def)) {
            return new QuestEngine.Avail(QuestState.LOCKED, 0);
        }
        if (def.type().board() && !engine.board().offered(hp, def)) {
            return new QuestEngine.Avail(QuestState.LOCKED, 0);
        }
        if (abandonCooldown(hp.uuid(), def).isPresent()) {
            return new QuestEngine.Avail(QuestState.LOCKED, 0);
        }
        Requirements.Outcome outcome = Requirements.evaluate(def.requires(), facts(hp));
        return outcome.met()
                ? new QuestEngine.Avail(QuestState.AVAILABLE, 0)
                : new QuestEngine.Avail(QuestState.LOCKED, outcome.lockedWeek());
    }

    boolean inWindow(QuestDef def) {
        QuestDef.TimeWindow window = def.timeWindow();
        if (window == null) {
            return true;
        }
        LocalDateTime now = engine.api.time().now().toLocalDateTime();
        LocalDateTime start = window.start();
        LocalDateTime end = window.end();
        return (start == null || !now.isBefore(start)) && (end == null || now.isBefore(end));
    }

    Requirements.Facts facts(HarborPlayer hp) {
        UUID uuid = hp.uuid();
        QuestData data = hp.get(QuestEngine.COMPONENT);
        return new Requirements.Facts() {
            @Override
            public boolean completed(QuestId quest) {
                QuestEntry e = data.entries().get(quest);
                return e != null && e.state() == QuestState.COMPLETED;
            }

            @Override
            public int level() {
                return hp.level();
            }

            @Override
            public int jobTier() {
                return hp.jobTier().level();
            }

            @Override
            public boolean playerFlag(String flag) {
                return engine.api.flags().hasPlayer(uuid, flag);
            }

            @Override
            public boolean serverFlag(String flag) {
                return engine.api.flags().hasServer(flag);
            }

            @Override
            public int week() {
                return engine.deps.clock().week();
            }

            @Override
            public int affinityLevel(NpcId npc) {
                return engine.deps.affinity().level(uuid, npc);
            }

            @Override
            public int reputationTier() {
                return engine.deps.reputation().tier(uuid);
            }
        };
    }

    /** 목표 역색인: 목표 유형 → 지금 진행되는 목표가 있는 퀘스트 (docs 20 6.4, sequence는 현재 목표만). */
    Map<String, List<QuestId>> index(HarborPlayer hp) {
        Map<String, List<QuestId>> cached = index.get(hp.uuid());
        if (cached != null) {
            return cached;
        }
        Map<String, List<QuestId>> built = new HashMap<>();
        for (QuestEntry e : hp.get(QuestEngine.COMPONENT).entries().values()) {
            if (e.state() != QuestState.ACTIVE) {
                continue;
            }
            engine.def(e.quest())
                    .ifPresent(
                            d -> {
                                for (Objective o : QuestMachine.current(d, e.progress())) {
                                    List<QuestId> list =
                                            built.computeIfAbsent(o.type(), t -> new ArrayList<>());
                                    if (!list.contains(d.id())) {
                                        list.add(d.id());
                                    }
                                }
                            });
        }
        index.put(hp.uuid(), built);
        return built;
    }

    /** 진행 중인 퀘스트 정의·행 (정의가 사라진 ORPHANED는 빠진다). */
    List<Map.Entry<QuestDef, QuestEntry>> open(HarborPlayer hp) {
        List<Map.Entry<QuestDef, QuestEntry>> result = new ArrayList<>();
        for (QuestEntry e : hp.get(QuestEngine.COMPONENT).entries().values()) {
            if (e.state() == QuestState.ACTIVE || e.state() == QuestState.READY) {
                engine.def(e.quest()).ifPresent(d -> result.add(Map.entry(d, e)));
            }
        }
        result.sort(Comparator.comparing(x -> x.getKey().id().id()));
        return result;
    }

    /** 포기한 뒤 같은 cycle을 다시 받기까지 대기 시작 (제안값 10분, 메모리). */
    void startAbandonCooldown(UUID uuid, QuestId quest, String cycle) {
        int minutes = engine.balance().integer("quest.abandon_cooldown_minutes", 10);
        abandonCooldown.put(
                uuid + "|" + quest + "|" + cycle, Instant.now().plus(Duration.ofMinutes(minutes)));
    }

    /** 포기 후 같은 cycle 재수락 대기 (제안값 10분, 메모리). */
    Optional<Instant> abandonCooldown(UUID uuid, QuestDef def) {
        String key = uuid + "|" + def.id() + "|" + engine.cycleOf(def);
        Instant until = abandonCooldown.get(key);
        if (until == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(until)) {
            abandonCooldown.remove(key);
            return Optional.empty();
        }
        return Optional.of(until);
    }
}
