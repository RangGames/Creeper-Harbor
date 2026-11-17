package wiki.creeper.harbor.common.redis;

/**
 * 형식이 있는 Redis 채널. 메시지는 record로 정의한다.
 *
 * @param name 채널 이름. {@code harbor:}로 시작한다 ('11. 데이터 설계' 4장)
 * @param type 메시지 record 클래스
 */
public record Channel<T extends Record>(String name, Class<T> type) {

    public Channel {
        if (!name.startsWith("harbor:")) {
            throw new IllegalArgumentException("채널 이름은 harbor:로 시작해야 합니다: " + name);
        }
    }
}
