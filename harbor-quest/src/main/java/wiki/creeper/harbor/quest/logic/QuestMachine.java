package wiki.creeper.harbor.quest.logic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.content.Objective;
import wiki.creeper.harbor.quest.content.ObjectiveMode;
import wiki.creeper.harbor.quest.content.QuestDef;

/**
 * 퀘스트 상태 기계와 목표 진행 계산 (docs 20 6.4). 순수 계산만 한다 (서버 API·입출력 없음).
 *
 * <pre>
 * LOCKED ⇄ AVAILABLE → ACTIVE → READY → COMPLETING → COMPLETED (반복이면 다음 cycle에 AVAILABLE)
 *                         ↘ ABANDONED(행 삭제) · FAILED(이벤트 기간 종료)        ↘ 실패 시 READY
 * </pre>
 */
public final class QuestMachine {

    private QuestMachine() {}

    /** 진행 결과. */
    public record Step(Progress progress, boolean changed, List<String> completedKeys) {}

    /** 지금 진행되는 목표 (sequence는 현재 목표 하나, all은 아직 못 채운 목표 전부). deliver는 보고할 때 보므로 빠진다. */
    public static List<Objective> current(QuestDef quest, Progress progress) {
        List<Objective> result = new ArrayList<>();
        List<Objective> objectives = quest.objectives();
        if (quest.mode() == ObjectiveMode.SEQUENCE) {
            int i = progress.index();
            if (i >= 0 && i < objectives.size()) {
                Objective o = objectives.get(i);
                if (o.tracked() && progress.count(o.key()) < o.count()) {
                    result.add(o);
                }
            }
            return result;
        }
        for (Objective o : objectives) {
            if (o.tracked() && progress.count(o.key()) < o.count()) {
                result.add(o);
            }
        }
        return result;
    }

    /** 추적 목표를 모두 채웠는지 (deliver 제외). */
    public static boolean ready(QuestDef quest, Progress progress) {
        for (Objective o : quest.objectives()) {
            if (o.tracked() && progress.count(o.key()) < o.count()) {
                return false;
            }
        }
        return true;
    }

    /** 목표를 amount만큼 진행한다. 지금 진행되는 목표가 아니면 바뀌지 않는다 (sequence 순서 보호). 목표 수를 넘지 않는다. */
    public static Step add(QuestDef quest, Progress progress, String key, int amount) {
        if (amount <= 0) {
            return new Step(progress, false, List.of());
        }
        Objective target = activeObjective(quest, progress, key);
        if (target == null) {
            return new Step(progress, false, List.of());
        }
        int before = progress.count(key);
        int after = (int) Math.min((long) target.count(), (long) before + amount);
        return apply(quest, progress, key, before, after, target);
    }

    /** 게이지 목표 (도달 값): 지금 값으로 덮어쓴다. 내려가지는 않는다 (한 번 채운 목표 유지). */
    public static Step set(QuestDef quest, Progress progress, String key, int value) {
        Objective target = activeObjective(quest, progress, key);
        if (target == null) {
            return new Step(progress, false, List.of());
        }
        int before = progress.count(key);
        int after = Math.min(target.count(), Math.max(before, value));
        return apply(quest, progress, key, before, after, target);
    }

    /** 관리 명령: 값을 그대로 넣는다 (순서와 무관). */
    public static Progress force(QuestDef quest, Progress progress, String key, int value) {
        Map<String, Integer> counts = new LinkedHashMap<>(progress.counts());
        quest.objective(key)
                .ifPresent(o -> counts.put(key, Math.max(0, Math.min(o.count(), value))));
        return progress.withCounts(counts, firstOpen(quest, counts));
    }

    private static Step apply(
            QuestDef quest,
            Progress progress,
            String key,
            int before,
            int after,
            Objective target) {
        if (after == before) {
            return new Step(progress, false, List.of());
        }
        Map<String, Integer> counts = new LinkedHashMap<>(progress.counts());
        counts.put(key, after);
        int index = quest.mode() == ObjectiveMode.SEQUENCE ? firstOpen(quest, counts) : 0;
        List<String> done = after >= target.count() ? List.of(key) : List.of();
        return new Step(progress.withCounts(counts, index), true, done);
    }

    private static @Nullable Objective activeObjective(
            QuestDef quest, Progress progress, String key) {
        for (Objective o : current(quest, progress)) {
            if (o.key().equals(key)) {
                return o;
            }
        }
        return null;
    }

    /** sequence: 아직 못 채운 첫 추적 목표의 순서 (모두 채웠으면 목표 수). */
    static int firstOpen(QuestDef quest, Map<String, Integer> counts) {
        List<Objective> objectives = quest.objectives();
        for (int i = 0; i < objectives.size(); i++) {
            Objective o = objectives.get(i);
            if (o.tracked() && counts.getOrDefault(o.key(), 0) < o.count()) {
                return i;
            }
        }
        return objectives.size();
    }

    /**
     * 리로드 때 진행 이전 (docs 20 6.4 예외 상황): 목표 key로 진행을 옮겨 붙인다. 없어진 목표는 버리고, 목표 수가 줄면 초과분을 자르고, 새 목표는
     * 0부터.
     */
    public static Progress migrate(QuestDef quest, Progress progress) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Objective o : quest.objectives()) {
            int value = progress.count(o.key());
            if (value > 0) {
                counts.put(o.key(), Math.min(value, o.count()));
            }
        }
        int index = quest.mode() == ObjectiveMode.SEQUENCE ? firstOpen(quest, counts) : 0;
        return progress.withCounts(counts, index).withVersion(quest.hash());
    }

    /** 진행 뒤 상태: 추적 목표를 모두 채우면 READY, 리로드로 다시 못 채운 상태가 되면 ACTIVE. */
    public static QuestState afterProgress(QuestState state, boolean ready) {
        if (state == QuestState.ACTIVE && ready) {
            return QuestState.READY;
        }
        if (state == QuestState.READY && !ready) {
            return QuestState.ACTIVE;
        }
        return state;
    }

    /** 수락할 수 있는 상태인지 (반복 퀘스트는 cycle이 바뀌면 COMPLETED도 다시 수락 가능). */
    public static boolean canAccept(QuestState state, boolean sameCycle) {
        return switch (state) {
            case LOCKED, ACTIVE, READY, COMPLETING, ORPHANED -> false;
            case AVAILABLE, ABANDONED -> true;
            case COMPLETED, FAILED -> !sameCycle;
        };
    }

    public static boolean canAbandon(QuestDef quest, QuestState state) {
        return quest.abandonable()
                && !quest.main()
                && (state == QuestState.ACTIVE || state == QuestState.READY);
    }

    public static boolean canTurnIn(QuestState state) {
        return state == QuestState.READY;
    }
}
