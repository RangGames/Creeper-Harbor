package wiki.creeper.harbor.wildlife.content;

import java.util.Map;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * balance.yml wildlife 절 ('17-3' 9.3·9.4). 값이 없으면 문서 값 또는 제안값. 문서 9.4 예산 표·LOD 표·위협 감지 값은 모두 제안값이다.
 */
public record WildlifeBalance(Balance b) {

    // ---------------------------------------------------------------- 예산 (9.4 표)
    /** 플레이어당 목표 마릿수. */
    public int targetPerPlayer() {
        return b.integer("wildlife.budget.target_per_player", 12);
    }

    /** 플레이어당 표시 상한 (마릿수). */
    public int maxShownPerPlayer() {
        return b.integer("wildlife.budget.max_shown", 24);
    }

    /** 플레이어당 표시 상한 (부품). */
    public int maxPartsPerPlayer() {
        return b.integer("wildlife.budget.max_parts", 180);
    }

    /** 서버 전체 상한. */
    public int globalMax() {
        return b.integer("wildlife.budget.global_max", 2000);
    }

    public int schoolsPerPlayerPerSecond() {
        return b.integer("wildlife.budget.schools_per_player_s", 2);
    }

    public int spawnPerTick() {
        return b.integer("wildlife.budget.spawn_per_tick", 20);
    }

    /** Core 기준 플레이어 주변 부품 상한 (balance.yml rig.part_budget과 같은 값). */
    public int corePartBudget() {
        return b.integer("rig.part_budget", 300);
    }

    /** 연출 강도(0 최소 · 1 보통 · 2 최대, Fishing 설정)별 부품 예산 배율 (50 · 75 · 100%). */
    public double intensityScale(int level) {
        double fallback =
                switch (level) {
                    case 0 -> 0.5;
                    case 1 -> 0.75;
                    default -> 1.0;
                };
        return b.number("wildlife.budget.intensity." + level, fallback);
    }

    // ---------------------------------------------------------------- 스폰·정리
    public double spawnRingMin() {
        return b.number("wildlife.spawn.ring_min", 16);
    }

    public double spawnRingMax() {
        return b.number("wildlife.spawn.ring_max", 40);
    }

    /** 다른 무리 중심과 떨어져야 하는 거리. */
    public double schoolGap() {
        return b.number("wildlife.spawn.school_gap", 8);
    }

    /** 물·용암 스폰에 필요한 매질 깊이 (칸). */
    public int minLiquidDepth() {
        return b.integer("wildlife.spawn.min_depth", 2);
    }

    /** 공중 스폰에 필요한 여유 칸. */
    public int airClearance() {
        return b.integer("wildlife.spawn.air_clearance", 3);
    }

    /** 모든 플레이어에게서 이만큼 멀어지면 제거. */
    public double despawnDistance() {
        return b.number("wildlife.spawn.despawn", 56);
    }

    /** 몸길이가 이 값 미만이면 작은 무리 (3~6), 이상이면 큰 개체 (1~2). */
    public double smallLength() {
        return b.number("wildlife.spawn.small_length", 1.0);
    }

    public int smallSchoolMin() {
        return b.integer("wildlife.spawn.small_school.min", 3);
    }

    public int smallSchoolMax() {
        return b.integer("wildlife.spawn.small_school.max", 6);
    }

    public int largeSchoolMin() {
        return b.integer("wildlife.spawn.large_school.min", 1);
    }

    public int largeSchoolMax() {
        return b.integer("wildlife.spawn.large_school.max", 2);
    }

    /** 청크당 바닥 개체(조개류) 상한. */
    public int floorPerChunk() {
        return b.integer("wildlife.spawn.floor_per_chunk", 2);
    }

    /** 바닥 개체가 앉을 수 있는 블록 (바닐라 블록 키). */
    public String floorBlocks() {
        return b.string("wildlife.spawn.floor_blocks")
                .orElse("sand,gravel,suspicious_sand,suspicious_gravel,mud,clay");
    }

    /** 자연 스폰 등급 가중치 ('7. 밸런스 기획' 4.1, fishing.grade_weight와 같은 값). 전설·신화는 0 (자연 스폰 안 함). */
    public double gradeWeight(Grade grade) {
        return switch (grade) {
            case LEGENDARY, MYTHIC -> 0;
            default -> {
                double fishing =
                        b.number("fishing.grade_weight." + grade.key(), defaultGrade(grade));
                yield b.number("wildlife.spawn.grade_weight." + grade.key(), fishing);
            }
        };
    }

    private static double defaultGrade(Grade grade) {
        return switch (grade) {
            case COMMON -> 60;
            case UNCOMMON -> 25;
            case RARE -> 10;
            case EPIC -> 4;
            default -> 0;
        };
    }

    /** 물고기 대이동 등 boost 기본 배율 (World가 wildlifeBoost를 주지 않을 때). */
    public double defaultBoost() {
        return b.number("wildlife.spawn.boost", 2.0);
    }

    // ---------------------------------------------------------------- LOD (9.4 표)
    public double nearDistance() {
        return b.number("wildlife.lod.near", 16);
    }

    /** 이보다 먼 개체는 새로 보여 주지 않는다 (시제품 LOD 40). */
    public double showDistance() {
        return b.number("wildlife.lod.show", 40);
    }

    /** 이미 보이는 개체는 여기까지 유지 (경계 깜박임 방지). */
    public double keepDistance() {
        return b.number("wildlife.lod.keep", 44);
    }

    public int nearMoveTicks() {
        return b.integer("wildlife.lod.near_ticks", 2);
    }

    public int midMoveTicks() {
        return b.integer("wildlife.lod.mid_ticks", 4);
    }

    /** 자동 감속 1단계의 16~40블록 전송 간격. */
    public int midMoveTicksSlow() {
        return b.integer("wildlife.lod.mid_ticks_slow", 6);
    }

    /** 40블록 밖(시청자 없음) 조향 계산 주기. */
    public double farHz() {
        return b.number("wildlife.lod.far_hz", 2);
    }

    /** 시청자가 없어진 뒤 리그 엔티티를 지우기까지 (제안값). 그동안 다시 보이면 그대로 쓴다. */
    public int hiddenGraceSeconds() {
        return b.integer("wildlife.lod.hidden_grace_s", 5);
    }

    /** 다른 모듈 리그 부품 수를 다시 세는 주기 (Core 300개 기준 중 남은 몫 계산). */
    public int otherPartsRefreshSeconds() {
        return b.integer("wildlife.lod.other_parts_refresh_s", 5);
    }

    // ---------------------------------------------------------------- 계산·반영
    public int simPeriodMillis() {
        return b.integer("wildlife.sim.period_ms", 100);
    }

    /** 메인 스레드 반영 예산 (틱당). */
    public double applyBudgetMillis() {
        return b.number("wildlife.sim.apply_budget_ms", 1.0);
    }

    /** 반영이 이만큼 주기 넘게 밀리면 자동 감속. */
    public int lagCycles() {
        return b.integer("wildlife.sim.lag_cycles", 2);
    }

    /** 자동 감속 2단계의 표시 상한 배율. */
    public double lagShownScale() {
        return b.number("wildlife.sim.lag_shown_scale", 0.75);
    }

    // ---------------------------------------------------------------- 행동 (9.4)
    public double homeRadius() {
        return b.number("wildlife.move.home_radius", 24);
    }

    public double retargetMin() {
        return b.number("wildlife.move.retarget_s.min", 3);
    }

    public double retargetMax() {
        return b.number("wildlife.move.retarget_s.max", 6);
    }

    /** 주사위 간격 1초 ± 0.3초. */
    public double diceInterval() {
        return b.number("wildlife.move.dice_s", 1.0);
    }

    public double diceJitter() {
        return b.number("wildlife.move.dice_jitter_s", 0.3);
    }

    /** 쉬기 속도 배율 (1 − 0.85). */
    public double idleSpeed() {
        return b.number("wildlife.move.idle_speed", 0.15);
    }

    /** 수면 아래 유지 거리. */
    public double surfaceGap() {
        return b.number("wildlife.move.surface_gap", 0.5);
    }

    /** 앞 탐침 거리 = 속도 × 이 시간. */
    public double probeAheadSeconds() {
        return b.number("wildlife.move.probe_ahead_s", 1.5);
    }

    /** 몸길이 → 순항 속도 (블록/초) = 몸길이 × 이 값, 아래 범위로 자름 (제안값). */
    public double cruisePerLength() {
        return b.number("wildlife.move.cruise_per_length", 2.0);
    }

    public double cruiseMin() {
        return b.number("wildlife.move.cruise_min", 0.6);
    }

    public double cruiseMax() {
        return b.number("wildlife.move.cruise_max", 2.2);
    }

    /** 템플릿별 최대 회전 속도 (fish 180°/초, ray 90°/초, jelly 30°/초, 제안값). */
    public double turnRate(WildTemplate template) {
        double fallback =
                switch (template) {
                    case FISH -> 180;
                    case CEPH -> 120;
                    case RAY -> 90;
                    case SEAHORSE -> 60;
                    case JELLY -> 30;
                    case CLAM -> 0;
                };
        return b.number("wildlife.move.turn_rate." + template.key(), fallback);
    }

    /** 기울기 한계 (±45°, 해마 ±20°). */
    public double pitchLimit(WildTemplate template) {
        double fallback =
                template == WildTemplate.SEAHORSE ? 20 : template == WildTemplate.CLAM ? 0 : 45;
        return b.number("wildlife.move.pitch_limit." + template.key(), fallback);
    }

    /** 무리 조향 가중치 (분리·정렬·응집·대장 추종, 제안값). */
    public Map<String, Double> flockWeights() {
        return Map.of(
                "separation", b.number("wildlife.school.separation", 1.6),
                "alignment", b.number("wildlife.school.alignment", 0.8),
                "cohesion", b.number("wildlife.school.cohesion", 0.6),
                "leader", b.number("wildlife.school.leader", 1.0));
    }

    /** 무리 이웃 반경 (제안값). */
    public double flockRadius() {
        return b.number("wildlife.school.radius", 4);
    }

    /** 분리 거리 = 몸길이 × 이 값 (제안값). */
    public double separationLengths() {
        return b.number("wildlife.school.separation_lengths", 1.5);
    }

    // ---------------------------------------------------------------- 위협 (9.4)
    public double sneakFactor() {
        return b.number("wildlife.threat.sneak", 0.5);
    }

    public double boatFactor() {
        return b.number("wildlife.threat.boat", 1.3);
    }

    /** 이 속도 이상으로 다가오면 발동 (블록/초). */
    public double closingSpeed() {
        return b.number("wildlife.threat.closing_speed", 2.5);
    }

    /** 반경의 이 비율 안까지 들어오면 발동. */
    public double innerRatio() {
        return b.number("wildlife.threat.inner_ratio", 0.35);
    }

    public double fleeMinSeconds() {
        return b.number("wildlife.threat.flee_s.min", 3);
    }

    public double fleeMaxSeconds() {
        return b.number("wildlife.threat.flee_s.max", 5);
    }

    /** 도주 속도 ×(1 + 1.4). */
    public double fleeSpeed() {
        return b.number("wildlife.threat.flee_speed", 1.4);
    }

    /** 무리 안 전파 거리. */
    public double spreadRadius() {
        return b.number("wildlife.threat.spread", 4);
    }

    public double habituateSeconds() {
        return b.number("wildlife.threat.habituate_s", 30);
    }

    public double habituateFactor() {
        return b.number("wildlife.threat.habituate", 0.7);
    }

    /** 줄이 끊겼을 때 위협 반응을 주는 반경 (제안값). */
    public double lineBreakRadius() {
        return b.number("wildlife.threat.line_break_radius", 8);
    }

    // ---------------------------------------------------------------- 먹이 (9.4)
    public double baitRadius() {
        return b.number("wildlife.bait.radius", 10);
    }

    public int baitMax() {
        return b.integer("wildlife.bait.max", 2);
    }

    public double orbitRadius() {
        return b.number("wildlife.bait.orbit", 1.5);
    }

    /** 궤도 각속도 (도/초, 제안값). */
    public double orbitSpeed() {
        return b.number("wildlife.bait.orbit_speed", 40);
    }

    // ---------------------------------------------------------------- 매질 (9.4)
    /** 공중 높이 띠 (섬 높이 −10 ~ +20). */
    public double airBandBelow() {
        return b.number("wildlife.air.band_below", 10);
    }

    public double airBandAbove() {
        return b.number("wildlife.air.band_above", 20);
    }

    /** 공허의 바다 섬 기준 높이 (지역 ID 이름별, 제안값). */
    public double airBaseY(String regionName) {
        return b.number(
                "wildlife.air.base_y." + regionName, b.number("wildlife.air.base_y.default", 64));
    }

    /** 사인파 오르내림 진폭 (블록/초)·주기 (초). */
    public double airBobSpeed() {
        return b.number("wildlife.air.bob_speed", 0.35);
    }

    public double airBobPeriod() {
        return b.number("wildlife.air.bob_period_s", 4);
    }

    /** 매질 격자를 만들 높이 범위 (콘텐츠 월드 이름별, 제안값). */
    public int gridMinY(String world) {
        return b.integer(
                "wildlife.grid." + world + ".min_y", b.integer("wildlife.grid.default.min_y", 0));
    }

    public int gridMaxY(String world) {
        return b.integer(
                "wildlife.grid." + world + ".max_y", b.integer("wildlife.grid.default.max_y", 96));
    }

    /** 틱당 만들 청크 스냅샷 수 (메인 스레드 복사 비용 제한). */
    public int gridChunksPerTick() {
        return b.integer("wildlife.grid.chunks_per_tick", 4);
    }

    /** 고정 밝기 (용암 속 어종이 어둡지 않게). 매질별, −1이면 고정하지 않음. */
    public int fixedLight(Medium medium) {
        int fallback = medium == Medium.LAVA ? 15 : -1;
        return b.integer("wildlife.light." + medium.key(), fallback);
    }

    public String worldAlias(String world) {
        return b.string("world_alias." + world).orElse(world);
    }
}
