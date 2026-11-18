package wiki.creeper.harbor.boss.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.common.id.BossId;

/**
 * 보스 정의·조우 조회와 관리자 강제 시작·종료 (docs 20 10.2). 메인 스레드 전용 (정의 조회는 어디서나).
 *
 * <pre>{@code
 * BossService boss = Bukkit.getServicesManager().load(BossService.class);
 * if (boss != null && boss.inEncounter(uuid)) { ... }   // 보스전 중에는 상점·컷씬을 막는다
 * }</pre>
 */
public interface BossService {

    /** 시작 결과. */
    enum StartResult {
        STARTED,
        UNKNOWN_BOSS,
        /** 파티 인원이 min~max를 벗어남. */
        PARTY_SIZE,
        /** 파티원이 경기장 반경 밖에 있음. */
        OUT_OF_RANGE,
        /** 빈 경기장 슬롯이 없음 (또는 동시 조우 상한). */
        NO_SLOT,
        /** 파티원 누군가 이미 조우 중. */
        BUSY,
        /** 같은 파티장의 소환 대기 시간. */
        COOLDOWN,
        /** BossEncounterStartEvent가 취소됨 (점검 등). */
        CANCELLED,
        /** 이 보스는 이 서버에서 시작할 수 없음 (인스턴스·월드 경기장). */
        WRONG_SERVER,
        /** 파티장이 아님. */
        NOT_LEADER
    }

    Collection<BossDef> definitions();

    Optional<BossDef> definition(BossId boss);

    /** 플레이어가 참가 중인 조우 (퇴장 뒤 슬롯 유지 중이면 그것도). */
    Optional<EncounterInfo> encounterOf(UUID player);

    boolean inEncounter(UUID player);

    List<EncounterInfo> encounters();

    /** 관리자 강제 시작 (소환 미끼 없이). 리더의 파티 전원을 리더 위치에서 가장 가까운 빈 슬롯으로 옮겨 시작한다 (반경 검사 생략). */
    StartResult forceStart(BossId boss, Player leader);

    /**
     * 관리자 강제 종료.
     *
     * @param result CLEARED · FAILED · ABORTED
     */
    boolean forceEnd(UUID encounterId, EncounterState result);
}
