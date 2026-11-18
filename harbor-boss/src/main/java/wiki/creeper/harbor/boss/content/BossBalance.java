package wiki.creeper.harbor.boss.content;

import wiki.creeper.harbor.boss.logic.ContributionWeights;
import wiki.creeper.harbor.common.content.type.Balance;

/**
 * balance.yml {@code boss} 절 (docs 20 10.4, '7. 밸런스 기획' 9장). 값이 없으면 문서 값(유형 표) 또는 제안값을 쓴다. 서버 API가
 * 없어 단위 테스트에서도 쓴다.
 */
public record BossBalance(Balance balance) {

    private static final String T = "boss.types.";

    /** 빈 balance (테스트용, 모두 기본값). */
    public static BossBalance defaults() {
        return new BossBalance(Balance.of(java.util.Map.of()));
    }

    // ------------------------------------------------------------------ 유형 기본값 ('7. 밸런스 기획' 9장)

    /** 인원당 체력 증가율: 입문 0.60 · 해역 0.50 · 차원 0.45 · 레이드 0.40. */
    public double perPlayerRate(BossType type) {
        double fallback =
                switch (type) {
                    case INTRO -> 0.60;
                    case SEA -> 0.50;
                    case DIMENSION -> 0.45;
                    case RAID -> 0.40;
                    case WORLD -> 0.0;
                };
        return balance.number(T + type.key() + ".per_player_rate", fallback);
    }

    /** 제한 시간 (초): 입문 360 · 해역 480 · 차원 720 · 레이드 1,200 · 월드 1,800. */
    public int timeLimitSeconds(BossType type) {
        int fallback =
                switch (type) {
                    case INTRO -> 360;
                    case SEA -> 480;
                    case DIMENSION -> 720;
                    case RAID -> 1200;
                    case WORLD -> 1800;
                };
        return balance.integer(T + type.key() + ".time_limit", fallback);
    }

    /** 주간 보상 횟수: 입문 5 · 해역 3 · 차원 2 · 레이드 1 · 월드 1. */
    public int weeklyLimit(BossType type) {
        int fallback =
                switch (type) {
                    case INTRO -> 5;
                    case SEA -> 3;
                    case DIMENSION -> 2;
                    case RAID, WORLD -> 1;
                };
        return Math.max(1, balance.integer(T + type.key() + ".weekly", fallback));
    }

    /** 클리어 골드 (제안값): 입문 300 · 해역 800 · 차원 1,500 · 레이드 3,000 · 월드 2,000. */
    public long clearGold(BossType type) {
        long fallback =
                switch (type) {
                    case INTRO -> 300;
                    case SEA -> 800;
                    case DIMENSION -> 1500;
                    case RAID -> 3000;
                    case WORLD -> 2000;
                };
        return (long) balance.number(T + type.key() + ".gold", (double) fallback);
    }

    /** 예고 시간 (틱, 제안값): 입문 1.5초 · 해역 1.2초 · 차원 1.0초 · 레이드 0.9초 · 월드 1.5초. */
    public int telegraphTicks(BossType type) {
        int fallback =
                switch (type) {
                    case INTRO, WORLD -> 30;
                    case SEA -> 24;
                    case DIMENSION -> 20;
                    case RAID -> 18;
                };
        return balance.integer(T + type.key() + ".telegraph_ticks", fallback);
    }

    /** 패턴 사이 기본 간격 (틱, 제안값). */
    public int patternGapTicks(BossType type) {
        int fallback =
                switch (type) {
                    case INTRO -> 100;
                    case SEA -> 80;
                    case DIMENSION -> 70;
                    case RAID -> 60;
                    case WORLD -> 60;
                };
        return balance.integer(T + type.key() + ".pattern_gap_ticks", fallback);
    }

    /** 시작에 필요한 앵커 수: 기본 1, 레이드 2 (docs 20 10.4 2단계). */
    public int anchorsMin(BossType type) {
        return balance.integer(T + type.key() + ".anchors_min", type == BossType.RAID ? 2 : 1);
    }

    // ------------------------------------------------------------------ 지연 보정 ('7. 밸런스 기획' 5장)

    public int latencyPingMs() {
        return balance.integer("boss.latency.ping_ms", 150);
    }

    /** 핑 150ms 이상 파티원이 있으면 예고 +0.2초. */
    public int latencyExtraTicks() {
        return balance.integer("boss.latency.extra_ticks", 4);
    }

    // ------------------------------------------------------------------ 진행

    public int prepareTicks() {
        return balance.integer("boss.prepare_seconds", 30) * 20;
    }

    public int introTicks() {
        return balance.integer("boss.intro_seconds", 5) * 20;
    }

    /** 걸기 게이지를 채우는 안전 구간 유지 시간 (제안값 20초). */
    public int hookTicks() {
        return (int) Math.round(balance.number("boss.hook.seconds", 20) * 20);
    }

    /** 앵커 줄이 끊기면 걸기 게이지를 이 값 이하로 되돌린다 (50%). */
    public double hookLineBreakCap() {
        return balance.number("boss.hook.line_break_cap", 0.5);
    }

    /** 앵커가 없을 때 걸기 게이지 감소 (초당 5%). */
    public double hookDecayPerSecond() {
        return balance.number("boss.hook.decay_per_second", 0.05);
    }

    /** 줄이 끊긴 뒤 다시 걸 때까지 (틱, 제안값). */
    public int hookReopenTicks() {
        return balance.integer("boss.hook.reopen_ticks", 40);
    }

    /** 앵커 교대 유예 (텐션 고정, 5초). */
    public int anchorGraceTicks() {
        return balance.integer("boss.hook.anchor_grace_seconds", 5) * 20;
    }

    public double anchorSafeMin() {
        return balance.number("boss.anchor.safe_min", 0.2);
    }

    public double anchorSafeMax() {
        return balance.number("boss.anchor.safe_max", 0.85);
    }

    /** 안전 구간 유지 중 초당 최대 체력 대비 감기 피해 (0.3%, 제안값). */
    public double anchorReelDamagePerSecond() {
        return balance.number("boss.anchor.reel_damage_per_second", 0.003);
    }

    /** 앵커 텐션 등급 배율 (보스 유형별 텐션 상승, 제안값). */
    public double anchorTensionMult(BossType type) {
        return balance.number(T + type.key() + ".tension_mult", 1.0);
    }

    public int weakIntervalTicks() {
        return (int) Math.round(balance.number("boss.counter.weak_interval_seconds", 12) * 20);
    }

    public int weakTicks() {
        return (int) Math.round(balance.number("boss.counter.weak_seconds", 4) * 20);
    }

    /** 앵커가 안전 구간을 유지하면 약점 노출 타이머가 이 배율로 빨리 찬다 (제안값). */
    public double weakSafeSpeedup() {
        return balance.number("boss.counter.safe_speedup", 1.5);
    }

    // ------------------------------------------------------------------ 피해

    public double weakMult() {
        return balance.number("boss.damage.weak", 3.0);
    }

    public double normalMult() {
        return balance.number("boss.damage.normal", 1.0);
    }

    public double armorMult() {
        return balance.number("boss.damage.armor", 0.2);
    }

    /** 작살 근접 찌르기 배율 (추가 제안). */
    public double stabMult() {
        return balance.number("boss.damage.stab", 0.5);
    }

    public int stabCooldownTicks() {
        return balance.integer("boss.damage.stab_cooldown_ticks", 10);
    }

    /** 작살 기본 피해가 아이템 stats에 없을 때 (제안값). */
    public double harpoonBaseDamage() {
        return balance.number("boss.damage.harpoon_base", 20);
    }

    /** 작살 추적 최대 틱 (제안값). */
    public int harpoonTrackTicks() {
        return balance.integer("boss.damage.harpoon_track_ticks", 60);
    }

    // ------------------------------------------------------------------ QTE

    public int qteRounds() {
        return balance.integer("boss.qte.rounds", 3);
    }

    public int qteInputs() {
        return balance.integer("boss.qte.inputs", 4);
    }

    public int qteWindowTicks() {
        return (int) Math.round(balance.number("boss.qte.window_seconds", 1.0) * 20);
    }

    public double qtePass() {
        return balance.number("boss.qte.pass", 0.6);
    }

    public int qteWin() {
        return balance.integer("boss.qte.win", 2);
    }

    public int qteLose() {
        return balance.integer("boss.qte.lose", 2);
    }

    /** 줄다리기에 실패하면 회복하는 체력 비율 (20%, 1회까지). */
    public double qteRecover() {
        return balance.number("boss.qte.recover", 0.2);
    }

    /** 라운드 사이 쉬는 틱 (제안값). */
    public int qteGapTicks() {
        return balance.integer("boss.qte.gap_ticks", 30);
    }

    // ------------------------------------------------------------------ 기여도

    public ContributionWeights weights() {
        return new ContributionWeights(
                balance.number("boss.contribution.damage", 0.3),
                balance.number("boss.contribution.tension", 0.3),
                balance.number("boss.contribution.repair", 0.2),
                balance.number("boss.contribution.evasion", 0.2));
    }

    /** 상위 보상 비율 (20%, 올림). */
    public double topRatio() {
        return balance.number("boss.contribution.top_ratio", 0.2);
    }

    /** 최소 참여: 점수가 최고점의 5% 미만이고 */
    public double minScoreRatio() {
        return balance.number("boss.contribution.min_score_ratio", 0.05);
    }

    /** 활동 시간이 전투 시간의 30% 미만이면 보상 없음. */
    public double minActiveRatio() {
        return balance.number("boss.contribution.min_active_ratio", 0.3);
    }

    /** 마지막 활동 뒤 이 시간까지 활동 중으로 센다 (제안값). */
    public int activeWindowTicks() {
        return balance.integer("boss.contribution.active_window_seconds", 5) * 20;
    }

    /** 보급관 버프 요리 1회 지원량 = 근처 파티원 수 × 이 초 (제안값). */
    public int supplySeconds() {
        return balance.integer("boss.contribution.supply_seconds", 60);
    }

    // ------------------------------------------------------------------ 보상

    /** 주간 횟수를 넘긴 클리어의 골드 비율 (20%). */
    public double overLimitGoldRatio() {
        return balance.number("boss.loot.over_limit_gold_ratio", 0.2);
    }

    /** 보급관이 있으면 드롭 확률에 더하는 값 (추가 제안). */
    public double quartermasterBonus() {
        return balance.number("boss.loot.quartermaster_bonus", 0.1);
    }

    /** 보스 어종 품질 보너스: 행운 +0.3 (제안값). */
    public double captureLuck() {
        return balance.number("boss.loot.capture_luck", 0.3);
    }

    public int captureQualityStars() {
        return balance.integer("boss.loot.capture_quality_stars", 1);
    }

    // ------------------------------------------------------------------ 이탈·한도

    /** 퇴장 뒤 슬롯 유지 (60초). */
    public int slotHoldTicks() {
        return balance.integer("boss.leave.slot_hold_seconds", 60) * 20;
    }

    /** 파티원 전원 이탈이 이만큼 이어지면 실패 (60초). */
    public int allAbsentFailTicks() {
        return balance.integer("boss.leave.all_absent_fail_seconds", 60) * 20;
    }

    /** 낙수 뒤 갑판 복귀 (5초). */
    public int fallTicks() {
        return balance.integer("boss.leave.fall_seconds", 5) * 20;
    }

    /** 본 서버 field 동시 조우 상한 (6, 제안값). */
    public int maxFieldEncounters() {
        return balance.integer("boss.limits.field", 6);
    }

    /** 인스턴스 서버 동시 조우 상한 (4, 제안값). */
    public int maxInstanceEncounters() {
        return balance.integer("boss.limits.instance", 4);
    }

    public int inviteSeconds() {
        return balance.integer("boss.party.invite_seconds", 60);
    }

    // ------------------------------------------------------------------ 월드 보스

    /** 최소 유효 참여 인원 (10명, 확정). */
    public int worldMinParticipants() {
        return balance.integer("boss.world.min_participants", 10);
    }

    /** 유효 참여 판정 시간 (최근 60초). */
    public int worldActiveTicks() {
        return balance.integer("boss.world.active_seconds", 60) * 20;
    }

    public int worldRescaleTicks() {
        return balance.integer("boss.world.rescale_seconds", 10) * 20;
    }

    public int worldBroadcastTicks() {
        return balance.integer("boss.world.broadcast_seconds", 2) * 20;
    }

    public int worldSaveTicks() {
        return balance.integer("boss.world.save_seconds", 10) * 20;
    }

    /** 본 서버가 죽은 뒤 이어서 진행할 수 있는 시간 (10분). */
    public int worldResumeMinutes() {
        return balance.integer("boss.world.resume_minutes", 10);
    }

    /** 월드 보스 표시·참여 반경 (64블록). */
    public double worldRadius() {
        return balance.number("boss.world.radius", 64);
    }

    /** 동시에 줄을 걸 수 있는 인원 (제안값). */
    public int worldMaxAnchors() {
        return balance.integer("boss.world.max_anchors", 20);
    }

    // ------------------------------------------------------------------ 인스턴스

    public int instanceSlots() {
        return balance.integer("boss.instance.slots", 4);
    }

    /** 같은 경기장 칸 사이 간격 (1,000블록, X축). */
    public double instanceSpacing() {
        return balance.number("boss.instance.spacing", 1000);
    }

    /** 배정 응답 대기 (10초). */
    public int instanceReplyTicks() {
        return balance.integer("boss.instance.reply_seconds", 10) * 20;
    }

    /** 예약한 칸의 확정 대기 (제안값). */
    public int instanceConfirmTicks() {
        return balance.integer("boss.instance.confirm_seconds", 15) * 20;
    }

    /** 파티원 도착 대기 (60초). */
    public int instanceArriveTicks() {
        return balance.integer("boss.instance.arrive_seconds", 60) * 20;
    }

    /** harbor:boss:rejoin 만료 (120초). */
    public int rejoinTtlSeconds() {
        return balance.integer("boss.instance.rejoin_ttl_seconds", 120);
    }
}
