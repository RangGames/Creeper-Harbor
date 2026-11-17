package wiki.creeper.harbor.core.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.regex.Pattern;
import wiki.creeper.harbor.common.model.SeasonId;

/**
 * 모듈이 플레이어에 붙이는 데이터 (예: Jobs의 스킬 배분, Collection의 도감 캐시). Core는 로드·저장 시점만 관리하고 내용은 모른다.
 *
 * <p>값은 변경 불가 객체(record, List.copyOf …)로 둔다. 바꿀 때는 {@link HarborPlayer#set}으로 새 값을 넣는다. 저장은 다른
 * 스레드에서 하므로, 값을 직접 고치면 저장 중인 값과 섞일 수 있다.
 *
 * @param id 저장 키에 쓰는 이름 (소문자·숫자·밑줄)
 */
public record PlayerComponent<T>(String id, Loader<T> loader, Saver<T> saver) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_]+");

    public PlayerComponent {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("컴포넌트 ID 형식이 틀렸습니다: " + id);
        }
    }

    /** 접속 전에 가상 스레드에서 부른다. 데이터가 없으면 기본값을 돌려준다. */
    @FunctionalInterface
    public interface Loader<T> {
        T load(Connection connection, UUID player, SeasonId season) throws SQLException;
    }

    /** 지연 쓰기 큐 또는 퇴장 시 즉시 저장에서 부른다. */
    @FunctionalInterface
    public interface Saver<T> {
        void save(Connection connection, UUID player, SeasonId season, T value) throws SQLException;
    }
}
