package wiki.creeper.harbor.web.webhook;

import java.util.List;
import java.util.Map;

/**
 * 웹훅 대상 하나 (config.yml webhooks.targets).
 *
 * @param format discord 또는 json
 * @param secret json 서명 비밀 (비우면 서명하지 않음)
 * @param events 보낼 type glob 목록
 * @param where data 조건 (점으로 이은 경로 → 값, 모두 같아야 보냄)
 */
public record WebhookTarget(
        String name,
        String url,
        Format format,
        String secret,
        List<String> events,
        Map<String, String> where) {

    /** 보내는 모양. */
    public enum Format {
        DISCORD,
        JSON
    }

    public WebhookTarget {
        events = List.copyOf(events);
        where = Map.copyOf(where);
    }
}
