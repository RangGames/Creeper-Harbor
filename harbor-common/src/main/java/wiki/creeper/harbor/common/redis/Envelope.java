package wiki.creeper.harbor.common.redis;

import java.time.Instant;

/**
 * 채널로 오가는 메시지 봉투. 전송 형식은 {@code {type, server, sentAt, payload}} JSON.
 *
 * @param type 메시지 record 이름
 * @param server 보낸 서버 ID
 */
public record Envelope<T>(String type, String server, Instant sentAt, T payload) {}
