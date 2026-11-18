package wiki.creeper.harbor.boss.api;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.id.BossId;

/**
 * 월드 보스 (docs 20 10.4). 클라이맥스 개방 조건은 Quest가 판단해 {@link #start}를 부른다. harbor:lock:worldboss를 얻은 서버
 * 하나만 실행하고, 진행은 harbor:worldboss로 2초마다 전 서버에 방송한다. 메인 스레드에서 부르고, 결과 future도 메인 스레드에서 완료된다.
 */
public interface WorldBossService {

    /** 결과. */
    enum StartResult {
        STARTED,
        /** 다른 서버가 실행 중 (잠금을 얻지 못함). */
        LOCKED,
        ALREADY_RUNNING,
        UNKNOWN_BOSS,
        /** 경기장 월드가 이 서버에 없음. */
        NO_ARENA,
        FAILED
    }

    /** 가장 최근에 받은 진행 상태 (이 서버가 실행 중이 아니어도 방송으로 받은 값). */
    Optional<WorldBossStatus> status();

    /** 이 서버에서 실행 중인지. */
    boolean runningHere();

    CompletableFuture<StartResult> start(BossId boss);
}
