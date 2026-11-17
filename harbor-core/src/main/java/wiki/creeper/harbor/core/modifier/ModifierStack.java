package wiki.creeper.harbor.core.modifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.ModifierService.Breakdown;

/** 보정자 계산 (서버 API 없음, 단위 테스트 대상). 기본값 → ADD 합 → MULTIPLY 곱 → 상한·하한. */
public final class ModifierStack {

    private ModifierStack() {}

    public static Breakdown compute(
            double base, List<Modifier> modifiers, double min, double max, Instant now) {
        double add = 0;
        double multiply = 1;
        List<Modifier> applied = new ArrayList<>();
        for (Modifier modifier : modifiers) {
            if (modifier.isExpired(now)) {
                continue;
            }
            switch (modifier.op()) {
                case ADD -> add += modifier.value();
                case MULTIPLY -> multiply *= modifier.value();
            }
            applied.add(modifier);
        }
        double result = Math.clamp((base + add) * multiply, min, max);
        return new Breakdown(base, result, List.copyOf(applied), min, max);
    }
}
