package wiki.creeper.harbor.boss.encounter;

import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRole;
import wiki.creeper.harbor.boss.logic.ContributionCalc;
import wiki.creeper.harbor.boss.logic.Qte;
import wiki.creeper.harbor.fishing.api.TensionSession;

/** 조우 참가자 한 명의 실행 중 상태 (메인 스레드 전용). */
final class Member {

    final UUID uuid;
    final String name;
    BossRole role;

    /** 역할을 직접 골랐는지 (준비 단계). */
    boolean chose;

    /** 처음부터 참가했는지 (최단 기록 후보 조건). */
    final boolean fromStart;

    // 기여도 원자료
    double damage;
    long tensionTicks;
    double repair;
    double evasion;
    long activeTicks;
    long lastActivity = Long.MIN_VALUE / 2;

    // 상태
    /** 경기장 밖·접속 종료가 시작된 틱 (−1이면 있음). */
    long absentSince = -1;

    /** 슬롯을 놓아 조우에서 빠짐 (기여도는 유지). */
    boolean dropped;

    /** 낙수 복귀 틱 (−1이면 아님). */
    long fallenUntil = -1;

    /** 조작 반전 디버프 끝 틱. */
    long invertedUntil = -1;

    long lastStab = Long.MIN_VALUE / 2;
    Qte.InputState input = Qte.InputState.NONE;

    // 앵커
    @Nullable TensionSession session;

    /** 의도해서 닫는 중 (onEnd를 줄 끊김으로 보지 않게). */
    boolean closingSession;

    long reopenAt = -1;

    Qte.@Nullable Track track;
    @Nullable BossBar bar;

    Member(UUID uuid, String name, BossRole role, boolean fromStart) {
        this.uuid = uuid;
        this.name = name;
        this.role = role;
        this.fromStart = fromStart;
    }

    boolean present() {
        return absentSince < 0 && !dropped;
    }

    boolean fallen(long now) {
        return fallenUntil >= 0 && now < fallenUntil;
    }

    void activity(long now) {
        lastActivity = now;
    }

    ContributionCalc.Stats stats() {
        return new ContributionCalc.Stats(
                uuid, damage, tensionTicks / 20.0, repair, evasion, activeTicks);
    }
}
