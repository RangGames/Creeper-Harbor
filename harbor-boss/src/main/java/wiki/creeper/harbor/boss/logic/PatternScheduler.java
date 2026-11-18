package wiki.creeper.harbor.boss.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.content.BossDef;

/**
 * 페이즈별 패턴 고르기 (docs 20 10.4): YAML weight 가중치로 고르고, 같은 패턴 연속 2회 금지, 쿨타임은 패턴별로 센다. 패턴 사이에는 페이즈의 최소
 * 간격을 둔다.
 */
public final class PatternScheduler {

    private final Map<String, Long> readyAt = new HashMap<>();
    private @Nullable String last;
    private long nextAllowed;

    /** 다음 패턴. 간격·쿨타임 때문에 지금 고를 수 없으면 빈 값. */
    public Optional<BossDef.PatternDef> next(
            List<BossDef.PatternDef> candidates, long now, int gapTicks, SplittableRandom random) {
        if (now < nextAllowed || candidates.isEmpty()) {
            return Optional.empty();
        }
        List<BossDef.PatternDef> ready = new ArrayList<>();
        for (BossDef.PatternDef p : candidates) {
            if (readyAt.getOrDefault(p.id(), Long.MIN_VALUE) > now) {
                continue;
            }
            // 연속 금지: 패턴이 하나뿐인 페이즈는 예외
            if (candidates.size() > 1 && p.id().equals(last)) {
                continue;
            }
            ready.add(p);
        }
        if (ready.isEmpty()) {
            return Optional.empty();
        }
        int total = 0;
        for (BossDef.PatternDef p : ready) {
            total += p.weight();
        }
        int pick = random.nextInt(total);
        BossDef.PatternDef chosen = ready.getLast();
        for (BossDef.PatternDef p : ready) {
            pick -= p.weight();
            if (pick < 0) {
                chosen = p;
                break;
            }
        }
        readyAt.put(chosen.id(), now + chosen.cooldownTicks());
        last = chosen.id();
        nextAllowed = now + Math.max(1, gapTicks);
        return Optional.of(chosen);
    }

    /** 다음 패턴까지 최소 대기를 늦춘다 (페이즈 전환 직후 숨 돌릴 틈). */
    public void delay(long until) {
        nextAllowed = Math.max(nextAllowed, until);
    }

    public @Nullable String last() {
        return last;
    }
}
