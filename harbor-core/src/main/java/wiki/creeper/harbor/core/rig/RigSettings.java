package wiki.creeper.harbor.core.rig;

import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.rig.RigBaker;
import wiki.creeper.harbor.common.rig.Xform;

/**
 * 리그 재생 설정 (balance.yml rig 구역). 기본값은 balance.yml에 값이 없을 때만 쓴다.
 *
 * @param moveTicks 이동·방향 보간 기본 틱 (teleport_duration)
 * @param blendTicks 동작 전환 이어 붙이기 틱
 * @param lodDistance 가장 가까운 시청자가 이보다 멀면 애니메이션 갱신을 건너뛴다 (블록)
 * @param tickBudgetNanos 틱당 리그 갱신 예산. 넘으면 남은 리그는 다음 틱으로 미룬다 (차례 돌림)
 * @param hitboxInterval 판정 상자 위치 갱신 주기 (틱)
 * @param partBudget 플레이어 1명 주변 표시 부품 기준 ('16. 플러그인 설계' 7장). 넘으면 /harbor rig stats에서 경고
 */
record RigSettings(
        RigBaker.Settings bake,
        int moveTicks,
        int blendTicks,
        double lodDistance,
        long tickBudgetNanos,
        int hitboxInterval,
        int partBudget) {

    static RigSettings from(Balance b) {
        int fps = b.integer("rig.fps", 10);
        if (fps <= 0 || 20 % fps != 0) {
            fps = 10; // CoreContent 검사가 막지만, 검사 전 값이 들어와도 멈추지 않게
        }
        Xform.Tolerance tolerance =
                new Xform.Tolerance(
                        b.number("rig.compress.position", 0.005),
                        b.number("rig.compress.rotation", 1.2),
                        b.number("rig.compress.scale", 0.005));
        int maxGap = Math.max(1, b.integer("rig.compress.max_gap", 8));
        return new RigSettings(
                new RigBaker.Settings(fps, tolerance, maxGap),
                Math.max(0, b.integer("rig.move_ticks", 2)),
                Math.max(0, b.integer("rig.blend_ticks", 4)),
                Math.max(1, b.number("rig.lod_distance", 48)),
                (long) (Math.max(0.05, b.number("rig.tick_budget_ms", 1.0)) * 1_000_000),
                Math.max(1, b.integer("rig.hitbox_interval", 2)),
                Math.max(1, b.integer("rig.part_budget", 300)));
    }
}
