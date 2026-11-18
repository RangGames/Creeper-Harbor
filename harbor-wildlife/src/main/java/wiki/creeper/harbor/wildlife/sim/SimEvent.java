package wiki.creeper.harbor.wildlife.sim;

import java.util.UUID;
import wiki.creeper.harbor.wildlife.api.Medium;

/** 계산 스레드가 한 주기 동안 만든 알림. 메인 스레드가 반영한다. */
public sealed interface SimEvent {

    /** 플레이어 때문에 처음 놀란 개체 (무리 전파분은 없음). */
    record Startle(UUID fish, UUID player) implements SimEvent {}

    /**
     * 동작 파티클 (16블록 이하 시청자에게만, '17-3' 9.4 LOD 표).
     *
     * @param key 바닐라 파티클 키 (용암이면 메인이 lava·smoke로 바꾼다)
     */
    record Particle(
            UUID fish, String world, double x, double y, double z, String key, Medium medium)
            implements SimEvent {}

    /** 개체가 없어졌다. */
    record Removed(UUID fish, Reason reason) implements SimEvent {}

    /** 없어진 이유. */
    enum Reason {
        DESPAWN,
        EXPIRED,
        CHUNK_UNLOAD,
        CAUGHT,
        REQUESTED,
        PATH_END
    }
}
