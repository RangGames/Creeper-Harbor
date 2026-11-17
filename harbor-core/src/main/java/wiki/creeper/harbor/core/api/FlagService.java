package wiki.creeper.harbor.core.api;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 진행 플래그 (예: climax_done, cutscene/ch1_intro). Quest보다 먼저 적재되는 Fishing·NPC·Collection도 읽고 쓸 수 있도록
 * Core에 둔다 (docs 20 0.2).
 *
 * <ul>
 *   <li>플레이어 플래그: 접속 중에는 메모리 캐시에서 읽고, 쓰기는 즉시 DB에 반영한다. 범위는 시즌(시즌 전환 때 초기화)과 영구 두 가지
 *   <li>서버 플래그: 모든 서버가 같이 쓰는 진행도 (예: climax_done). 바뀌면 Redis로 다른 서버에 알린다
 * </ul>
 *
 * 읽기·쓰기는 메인 스레드에서 한다. 바뀌면 PlayerFlagChangedEvent·ServerFlagChangedEvent가 발생한다.
 */
public interface FlagService {

    /** 플래그 범위. */
    enum Scope {
        /** 시즌 전환 때 초기화. */
        SEASON,
        /** 영구 (메인 스토리, 컷씬 본 기록). */
        PERM
    }

    Pattern NAME = Pattern.compile("[a-z0-9_]+(/[a-z0-9_]+)*");

    static String requireName(String flag) {
        if (!NAME.matcher(flag).matches()) {
            throw new IllegalArgumentException("플래그 이름 형식이 틀렸습니다: " + flag);
        }
        return flag;
    }

    boolean hasPlayer(UUID player, String flag);

    Optional<String> playerValue(UUID player, String flag);

    /** 플레이어 플래그를 켠다 (value는 기본 "1"). 접속 중이 아니어도 DB에 쓴다. */
    CompletableFuture<Void> setPlayer(UUID player, Scope scope, String flag, String value);

    default CompletableFuture<Void> setPlayer(UUID player, Scope scope, String flag) {
        return setPlayer(player, scope, flag, "1");
    }

    CompletableFuture<Void> unsetPlayer(UUID player, String flag);

    boolean hasServer(String flag);

    Optional<String> serverValue(String flag);

    /** 서버 플래그. seasonal이면 시즌 전환 때 초기화한다. */
    CompletableFuture<Void> setServer(String flag, String value, boolean seasonal);

    CompletableFuture<Void> unsetServer(String flag);
}
