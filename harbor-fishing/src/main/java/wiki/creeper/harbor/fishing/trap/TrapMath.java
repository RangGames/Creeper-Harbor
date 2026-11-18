package wiki.creeper.harbor.fishing.trap;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.Quality;

/**
 * 통발 계산 (docs 20 3.4 TrapService, '7. 밸런스 기획' 8장, 결정 D1). 서버 API를 쓰지 않는다 (단위 테스트).
 *
 * <ul>
 *   <li>주기 = 8시간 × cycle_mult + trap_cycle_hours (통발 회전 −1·−2시간, 하한 min_cycle_h)
 *   <li>쌓인 주기 = min(floor((지금 − last_calc) ÷ 주기), 2). 2주기를 넘게 비우면 넘친 시간은 버린다
 *   <li>주기마다 미끼가 남은 슬롯(slots + 확장 + trap_slots) 수만큼 1마리, 슬롯당 미끼 1개. 풍어 통발이면 주기마다 확률로 1마리 추가(미끼 없음)
 *   <li>수익 상한: 계정의 모든 통발 24시간 산출 가치 합 ≤ 수동 낚시 1시간 골드 × 비율(0.4 / 생산 2차 0.5 / 3차 0.6). 넘으면 남은 슬롯은
 *       산출하지 않고 미끼도 쓰지 않는다
 * </ul>
 */
public final class TrapMath {

    /**
     * 통발 아이템 stats (items의 type: trap).
     *
     * @param slots 기본 슬롯 (나무 3 · 철 6 · 산호 9 · 심해 12)
     * @param maxGrade 산출 최고 등급 (stats.max_grade 1 일반 ~ 4 영웅)
     * @param cycleMult 주기 배율 (산호 0.9)
     * @param tier 통발 단계 (나무 1 · 철 2 · 산호 3 · 심해 4, 슬롯 확장 가격)
     * @param deep 심해 통발 (설치에 feature_deep_trap 필요)
     */
    public record Spec(int slots, Grade maxGrade, double cycleMult, int tier, boolean deep) {

        public static Spec of(Map<String, Double> stats) {
            int maxGrade = (int) Math.round(stats.getOrDefault("max_grade", 1.0));
            Grade grade =
                    switch (Math.clamp(maxGrade, 1, 4)) {
                        case 1 -> Grade.COMMON;
                        case 2 -> Grade.UNCOMMON;
                        case 3 -> Grade.RARE;
                        default -> Grade.EPIC;
                    };
            return new Spec(
                    (int) Math.max(1, Math.round(stats.getOrDefault("slots", 3.0))),
                    grade,
                    Math.max(0.1, stats.getOrDefault("cycle_mult", 1.0)),
                    (int) Math.max(1, Math.round(stats.getOrDefault("tier", 1.0))),
                    stats.getOrDefault("deep", 0.0) > 0);
        }
    }

    /** 산출할 한 마리 (어종·크기·품질·가치). */
    public record Rolled(FishDef fish, double sizeCm, Quality quality, double value) {}

    /** 어종 하나를 굴리는 함수. */
    @FunctionalInterface
    public interface Roller {
        Rolled next();
    }

    /** 쌓인 주기와 새 last_calc. */
    public record Accrual(int cycles, Instant lastCalc) {}

    /**
     * 산출 결과.
     *
     * @param baitUsed 쓴 미끼 수
     * @param value 이번에 더한 가치 (value_24h에 더함)
     * @param capReached 계정 수익 상한에 걸려 멈췄는지
     */
    public record Production(
            List<TrapCatch> catches, int baitUsed, double value, boolean capReached) {}

    private TrapMath() {}

    /** 주기 길이. delta는 trap_cycle_hours 스탯 (통발 회전이면 음수). */
    public static Duration cycle(
            double baseHours, double cycleMult, double delta, double minHours) {
        double hours = Math.max(minHours, baseHours * cycleMult + delta);
        return Duration.ofMillis(Math.round(hours * 3_600_000));
    }

    public static Accrual accrue(Instant lastCalc, Instant now, Duration cycle, int maxCycles) {
        if (!now.isAfter(lastCalc) || cycle.isZero() || cycle.isNegative()) {
            return new Accrual(0, lastCalc);
        }
        long raw = Duration.between(lastCalc, now).toMillis() / cycle.toMillis();
        if (raw >= maxCycles) {
            // 최대 주기를 넘게 비웠으면 넘친 시간은 버리고 지금부터 다시 센다
            return new Accrual(maxCycles, now);
        }
        return new Accrual((int) raw, lastCalc.plus(cycle.multipliedBy(raw)));
    }

    /** 통발 하나의 24시간 창 안 가치 (창이 지났으면 0). */
    public static double windowValue(
            double value, Instant windowStart, Instant now, Duration window) {
        return now.isBefore(windowStart.plus(window)) ? value : 0;
    }

    /** 계정 수익 상한 (G). */
    public static double accountCap(double hourlyGold, double ratio) {
        return Math.max(0, hourlyGold) * Math.max(0, ratio);
    }

    /** 한 주기에 쓰는 슬롯 수 = 통발 슬롯 + 확장 + 직업 보너스(trap_slots, 통발 1개당). */
    public static int effectiveSlots(int baseSlots, int extraSlots, double bonusSlots) {
        return Math.max(0, baseSlots + extraSlots + (int) Math.floor(bonusSlots));
    }

    /** 설치 가능 개수 = 기본 + trap_count (최대 max). */
    public static int maxTraps(int base, double trapCount, int max) {
        return Math.min(max, base + (int) Math.floor(Math.max(0, trapCount)));
    }

    /**
     * 주기만큼 산출한다.
     *
     * @param bait 남은 미끼
     * @param accountUsed 이 통발을 뺀 계정 24시간 산출 가치 + 이 통발의 창 안 가치
     * @param accountCap 계정 수익 상한
     */
    public static Production produce(
            int cycles,
            int slots,
            int bait,
            int baitPerSlot,
            double extraChance,
            double accountUsed,
            double accountCap,
            Roller roller,
            Instant at,
            RandomGenerator random) {
        List<TrapCatch> catches = new ArrayList<>();
        int baitLeft = bait;
        double used = accountUsed;
        double added = 0;
        boolean capped = false;
        outer:
        for (int cycle = 0; cycle < cycles; cycle++) {
            int perSlot = Math.max(1, baitPerSlot);
            int filled = Math.min(slots, baitLeft / perSlot);
            for (int i = 0; i < filled; i++) {
                Rolled r = roller.next();
                if (used + r.value() > accountCap) {
                    capped = true;
                    break outer;
                }
                used += r.value();
                added += r.value();
                baitLeft -= perSlot;
                catches.add(toCatch(r, at));
            }
            // 풍어 통발: 주기마다 확률로 1마리 추가 (미끼를 쓴 슬롯이 하나라도 있을 때)
            if (filled > 0 && extraChance > 0 && random.nextDouble() < extraChance) {
                Rolled r = roller.next();
                if (used + r.value() > accountCap) {
                    capped = true;
                    break;
                }
                used += r.value();
                added += r.value();
                catches.add(toCatch(r, at));
            }
        }
        return new Production(List.copyOf(catches), bait - baitLeft, added, capped);
    }

    private static TrapCatch toCatch(Rolled r, Instant at) {
        return new TrapCatch(
                UUID.randomUUID().toString(),
                r.fish().id().toString(),
                r.sizeCm(),
                r.quality().stars(),
                at.toEpochMilli());
    }
}
