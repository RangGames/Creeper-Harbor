package wiki.creeper.harbor.world.ship;

import java.util.Map;
import wiki.creeper.harbor.world.api.UpgradePath;
import wiki.creeper.harbor.world.content.ShipDef;

/** 선박 수치 계산 (docs 20 8.4 '선박'). 서버 API 없음 (단위 테스트). 수치는 모두 ships.yml·balance.yml에서 온다. */
public final class ShipMath {

    private ShipMath() {}

    /** 최대 내구도 = 기본 × (1 + 선체 업그레이드). */
    public static int maxDurability(ShipDef def, Map<UpgradePath, Integer> upgrades) {
        double hull =
                def.upgradeValue(UpgradePath.HULL, upgrades.getOrDefault(UpgradePath.HULL, 0));
        return (int) Math.round(def.durability() * (1 + hull));
    }

    /** 화물 칸 (9의 배수, 최대 54). */
    public static int cargo(ShipDef def, Map<UpgradePath, Integer> upgrades) {
        double extra =
                def.upgradeValue(UpgradePath.CARGO, upgrades.getOrDefault(UpgradePath.CARGO, 0));
        int slots = def.cargo() + (int) Math.round(extra);
        return Math.min(ShipDef.MAX_CARGO, (slots / 9) * 9);
    }

    /** 선박 속도 배율 (스탯 보정 전) = 1 + 선박 속도 + 속도 업그레이드. */
    public static double baseSpeed(ShipDef def, Map<UpgradePath, Integer> upgrades) {
        return 1
                + def.speed()
                + def.upgradeValue(UpgradePath.SPEED, upgrades.getOrDefault(UpgradePath.SPEED, 0));
    }

    /**
     * 최종 속도 배율 = 선박 배율 × boat_speed 스탯 × 폭풍 배율.
     *
     * @param boatSpeed boat_speed 스탯 (기본 1.0, 탐험 자동 패시브 +10% · 순풍 +5%/랭크)
     * @param stormMult 폭풍 중이면 events.yml storm.speed_mult (폭풍 항해 1랭크면 1)
     */
    public static double speed(double base, double boatSpeed, double stormMult) {
        return Math.max(0.1, base * boatSpeed * stormMult);
    }

    /**
     * 바닐라 보트의 물 위 종단 속도는 가속 a에 비례하므로, 틱마다 앞으로 a × (배율 − 1)을 더하면 종단 속도가 배율만큼 바뀐다 (주기적 속도 보정, Phase 1
     * 검증 항목).
     */
    public static double impulse(double accel, double multiplier) {
        return accel * (multiplier - 1);
    }

    /** 업그레이드 다음 단계 비용 = 선박 가격 × 단계 비율 (10 · 20 · 30%). */
    public static long upgradeCost(ShipDef def, double ratio) {
        return Math.round(def.price() * ratio);
    }

    /**
     * 폭풍 내구도 피해 (5초마다) = damage × boat_damage 스탯.
     *
     * @param boatDamage boat_damage 스탯 (기본 1.0, 탐험 자동 패시브 −0.10, 튼튼한 선체 −0.08/랭크)
     */
    public static double stormDamage(int damage, double boatDamage) {
        return Math.max(0, damage * boatDamage);
    }

    /** 수리비 = 선박 가격 × 비율 × repair_cost 스탯 (최소 1, 가격 0이면 무료). */
    public static long repairCost(long price, double rate, double repairCostStat) {
        if (price <= 0) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(price * rate * repairCostStat));
    }
}
