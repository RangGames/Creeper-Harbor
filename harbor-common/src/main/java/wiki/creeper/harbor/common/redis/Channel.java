package wiki.creeper.harbor.common.redis;

/**
 * Typed Redis channel. Messages are defined as records.
 *
 * @param name channel name. Starts with {@code harbor:} ('11. Data design' chapter 4)
 * @param type message record class
 */
public record Channel<T extends Record>(String name, Class<T> type) {

    public Channel {
        if (!name.startsWith("harbor:")) {
            throw new IllegalArgumentException("채널 이름은 harbor:로 시작해야 합니다: " + name);
        }
    }
}
