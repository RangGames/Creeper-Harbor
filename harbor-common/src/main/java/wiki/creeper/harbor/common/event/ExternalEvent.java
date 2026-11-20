package wiki.creeper.harbor.common.event;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * 외부 연동(웹훅·웹사이트·봇)에 내보내는 게임 이벤트 (추가 제안). Redis {@code harbor:events}로 발행되고, 메시지 봉투의 payload가 이
 * 레코드다.
 *
 * <pre>{@code
 * {"type":"ExternalEvent","server":"main-1","sentAt":"…",
 *  "payload":{"id":"…","type":"quest.completed","version":1,"server":"main-1",
 *             "occurredAt":"2026-11-20T12:00:00Z","player":"<uuid>","playerName":"Steve",
 *             "data":{"quest":"harbor:quest/main/ch1_first_cast", "quest_name":"첫 캐스팅", …}}}
 * }</pre>
 *
 * @param id 이벤트 고유 ID (UUID 글). 받는 쪽이 중복을 거를 때 쓴다
 * @param type {@code <모듈>.<사건>} (소문자·밑줄·점). 목록은 Harbor-Web README
 * @param version data 형식 버전. 필드를 빼거나 뜻을 바꿀 때만 올린다 (필드 추가는 그대로)
 * @param occurredAt ISO-8601 UTC
 * @param player 관련 플레이어 UUID (없으면 null)
 * @param data 사건 내용. 값은 글·수·참거짓·목록·지도뿐이다 (JSON 그대로)
 */
public record ExternalEvent(
        String id,
        String type,
        int version,
        String server,
        String occurredAt,
        @Nullable String player,
        @Nullable String playerName,
        Map<String, Object> data) {

    public ExternalEvent {
        data = Map.copyOf(data);
    }
}
