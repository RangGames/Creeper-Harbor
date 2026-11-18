package wiki.creeper.harbor.world.content;

import wiki.creeper.harbor.common.content.type.Balance;

/**
 * balance.yml world 절 (docs 20 8.4). 값이 없으면 문서 값 또는 제안값 (환경 디버프·선박 업그레이드 효과는 문서에 없어 모두 제안값, docs 20
 * 8.8).
 */
public record WorldBalance(Balance b) {

    // ---------------------------------------------------------------- 지역
    /** 경계에서 이만큼 떨어져 있을 때만 안전 위치로 저장 (블록). */
    public double safeDistance() {
        return b.number("world.region.safe_distance", 3);
    }

    public double pushSpeed() {
        return b.number("world.region.push_speed", 0.6);
    }

    public int dialogCooldownSeconds() {
        return b.integer("world.region.dialog_cooldown_s", 30);
    }

    public String worldAlias(String world) {
        return b.string("world_alias." + world).orElse(world);
    }

    // ---------------------------------------------------------------- 해도
    /** 보상 단계 n(1~4)의 완성도 (25 · 50 · 75 · 95%). */
    public double chartStep(int step) {
        double fallback =
                switch (step) {
                    case 1 -> 0.25;
                    case 2 -> 0.50;
                    case 3 -> 0.75;
                    default -> 0.95;
                };
        return b.number("world.chart.steps." + step, fallback);
    }

    public int chartSteps() {
        return b.integer("world.chart.step_count", 4);
    }

    /** 단계 골드 = 지역 시간당 골드 × 이 값 (제안값 25%). */
    public double chartStepGoldRatio() {
        return b.number("world.chart.step_gold_ratio", 0.25);
    }

    /** 완성(마지막 단계) 물비늘 ('7. 밸런스 기획' 7장 지역당 2). */
    public int chartCompleteMulbineul() {
        return b.integer("world.chart.complete_mulbineul", 2);
    }

    // ---------------------------------------------------------------- 보물 지도
    public double treasureFuzz() {
        return b.number("world.treasure.fuzz", 48);
    }

    public double treasureExactWithin() {
        return b.number("world.treasure.exact_within", 64);
    }

    public double treasureClaimRadius() {
        return b.number("world.treasure.claim_radius", 3);
    }

    public int treasureDigTicks() {
        return (int) Math.round(b.number("world.treasure.dig_seconds", 3) * 20);
    }

    // ---------------------------------------------------------------- 선박
    public double summonWaterRadius() {
        return b.number("world.ship.summon_water_radius", 3);
    }

    public int summonCooldownSeconds() {
        return b.integer("world.ship.summon_cooldown_s", 10);
    }

    /** 업그레이드 n단계 비용 비율 (선박 가격의 10 · 20 · 30%). */
    public double upgradeCost(int level) {
        double fallback =
                switch (level) {
                    case 1 -> 0.10;
                    case 2 -> 0.20;
                    default -> 0.30;
                };
        return b.number("world.ship.upgrade_cost." + level, fallback);
    }

    /** 수리비 비율 (Economy와 같은 economy.repair 값: 3%, 내구도 0이면 10%). */
    public double repairRate(boolean wrecked) {
        return wrecked
                ? b.number("economy.repair.broken_rate", 0.10)
                : b.number("economy.repair.rate", 0.03);
    }

    public double repairKitRatio() {
        return b.number("world.ship.repair_kit_ratio", 0.30);
    }

    public int repairKitTicks() {
        return (int) Math.round(b.number("world.ship.repair_kit_seconds", 5) * 20);
    }

    public int iceRestoreSeconds() {
        return b.integer("world.ship.ice_restore_s", 60);
    }

    /** 바닐라 보트 가속 (물 위 틱당). 속도 보정은 이 값에 (배율 − 1)을 곱해 앞으로 더한다 (Phase 1 검증 항목). */
    public double boatAcceleration() {
        return b.number("world.ship.vanilla_accel", 0.04);
    }

    public String starterShip() {
        return b.string("world.ship.starter").orElse("harbor:ship/rowboat");
    }

    // ---------------------------------------------------------------- 환경
    public double coldOutdoor() {
        return b.number("world.env.cold.outdoor", 1);
    }

    public double coldWater() {
        return b.number("world.env.cold.water", 3);
    }

    public double coldWarm() {
        return b.number("world.env.cold.warm", -2);
    }

    public int coldHeatRadius() {
        return b.integer("world.env.cold.heat_radius", 4);
    }

    public double coldSlowAt() {
        return b.number("world.env.cold.slow_at", 60);
    }

    public double coldMax() {
        return b.number("world.env.cold.max", 100);
    }

    public double coldDamage() {
        return b.number("world.env.cold.damage", 1);
    }

    public int coldDamageIntervalSeconds() {
        return b.integer("world.env.cold.damage_interval_s", 3);
    }

    public double oxygenMax() {
        return b.number("world.env.oxygen.max_s", 120);
    }

    public double oxygenNoHelmetMult() {
        return b.number("world.env.oxygen.no_helmet_mult", 2);
    }

    public double oxygenNautilusMult() {
        return b.number("world.env.oxygen.nautilus_mult", 0.5);
    }

    public double oxygenRecover() {
        return b.number("world.env.oxygen.recover", 5);
    }

    public double oxygenWarnRatio() {
        return b.number("world.env.oxygen.warn_ratio", 0.2);
    }

    public double oxygenDamage() {
        return b.number("world.env.oxygen.damage", 2);
    }

    public double airPocketRadius() {
        return b.number("world.env.oxygen.pocket_radius", 3);
    }

    public double heatBootsMult() {
        return b.number("world.env.heat.boots_mult", 0.5);
    }

    // ---------------------------------------------------------------- 물고기 떼
    public double spotRadius() {
        return b.number("world.spot.radius", 6);
    }

    public int spotMoveMinSeconds() {
        return b.integer("world.spot.move_min_s", 600);
    }

    public int spotMoveMaxSeconds() {
        return b.integer("world.spot.move_max_s", 1200);
    }

    public double spotParticleRadius() {
        return b.number("world.spot.particle_radius", 32);
    }

    /** 물고기 떼 캐스팅 입질 대기 배율 (−20%, 제안값). */
    public double spotBiteWait() {
        return b.number("world.spot.bite_wait", 0.8);
    }

    /** 물고기 떼 희귀 가중치 (+10%, 제안값). */
    public double spotRareWeight() {
        return b.number("world.spot.rare_weight", 0.10);
    }

    public int spotModifierSeconds() {
        return b.integer("world.spot.modifier_s", 45);
    }

    public double spotRadarRadius() {
        return b.number("world.spot.radar_radius", 64);
    }

    // ---------------------------------------------------------------- 탈것
    public int airshipIntervalSeconds() {
        return b.integer("world.airship.interval_s", 180);
    }

    public int airshipCapacity() {
        return b.integer("world.airship.capacity", 4);
    }

    /** 비행 속도 (블록/틱). */
    public double airshipSpeed() {
        return b.number("world.airship.speed", 1.2);
    }

    public double airshipAltitude() {
        return b.number("world.airship.altitude", 110);
    }

    public double airshipBoardRadius() {
        return b.number("world.airship.board_radius", 6);
    }

    public int nautilusReturnSeconds() {
        return b.integer("world.nautilus.return_after_s", 30);
    }

    // ---------------------------------------------------------------- 하우징
    public String housingWorld() {
        return b.string("world.housing.world").orElse("harbor_homes");
    }

    /** 플롯 칸 (32 최대 크기 + 길 8, 제안값). */
    public int housingCell() {
        return b.integer("world.housing.cell", 40);
    }

    public int housingBaseSize() {
        return b.integer("world.housing.base_size", 24);
    }

    public int housingLargeSize() {
        return b.integer("world.housing.large_size", 32);
    }

    /** 시즌 간 최고 평판 등급이 이 이상이면 큰 플롯 (항구의 일원 = 4). */
    public int housingLargeTier() {
        return b.integer("world.housing.large_tier", 4);
    }

    public int housingFloorY() {
        return b.integer("world.housing.floor_y", 64);
    }

    public int housingEntityLimit() {
        return b.integer("world.housing.entity_limit", 30);
    }

    public int housingRedstoneLimit() {
        return b.integer("world.housing.redstone_limit", 20);
    }

    public int housingAnchorLimit() {
        return b.integer("world.housing.anchor_limit", 12);
    }

    // ---------------------------------------------------------------- 기타
    public int occupancyPublishSeconds() {
        return b.integer("world.occupancy.publish_s", 60);
    }

    public int occupancyStaleSeconds() {
        return b.integer("world.occupancy.stale_s", 180);
    }

    public int eventCheckSeconds() {
        return b.integer("world.event.check_s", 60);
    }
}
