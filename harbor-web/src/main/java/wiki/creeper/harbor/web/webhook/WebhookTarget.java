package wiki.creeper.harbor.web.webhook;

import java.util.List;
import java.util.Map;

/**
 * One webhook target (config.yml webhooks.targets).
 *
 * @param format discord or json
 * @param secret json signing secret (empty means unsigned)
 * @param events type globs to send
 * @param where data conditions (dotted path → value; all must be equal to send)
 */
public record WebhookTarget(
        String name,
        String url,
        Format format,
        String secret,
        List<String> events,
        Map<String, String> where) {

    /** Payload shape. */
    public enum Format {
        DISCORD,
        JSON
    }

    public WebhookTarget {
        events = List.copyOf(events);
        where = Map.copyOf(where);
    }
}
