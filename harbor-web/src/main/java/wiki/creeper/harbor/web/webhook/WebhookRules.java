package wiki.creeper.harbor.web.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import wiki.creeper.harbor.common.event.ExternalEvent;
import wiki.creeper.harbor.web.relay.Globs;

/** 웹훅 거르기·서명·평탄화 (서버 API 없음, 단위 테스트 대상). */
public final class WebhookRules {

    private WebhookRules() {}

    /** type glob과 where 조건이 맞는지. */
    public static boolean accepts(WebhookTarget target, ExternalEvent event) {
        if (!Globs.any(target.events(), event.type())) {
            return false;
        }
        if (target.where().isEmpty()) {
            return true;
        }
        Map<String, String> flat = flatten(event.data());
        for (Map.Entry<String, String> w : target.where().entrySet()) {
            if (!w.getValue().equals(flat.get(w.getKey()))) {
                return false;
            }
        }
        return true;
    }

    /** {a: {b: 1}, c: [x, y]} → {a.b: "1", c: "x, y"}. 정수로 떨어지는 수는 소수점 없이. */
    public static Map<String, String> flatten(Map<String, Object> data) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten("", data, out);
        return out;
    }

    private static void flatten(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            map.forEach(
                    (k, v) ->
                            flatten(
                                    prefix.isEmpty() ? String.valueOf(k) : prefix + "." + k,
                                    v,
                                    out));
        } else if (value instanceof List<?> list) {
            StringBuilder b = new StringBuilder();
            for (Object x : list) {
                if (!b.isEmpty()) {
                    b.append(", ");
                }
                b.append(text(x));
            }
            out.put(prefix, b.toString());
        } else {
            out.put(prefix, text(value));
        }
    }

    static String text(Object v) {
        if (v instanceof Double d && d == Math.rint(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
            return String.valueOf(d.longValue());
        }
        if (v instanceof Float f && f == Math.rint(f)) {
            return String.valueOf(f.longValue());
        }
        return String.valueOf(v);
    }

    /** "sha256=" + hex(HMAC-SHA256(secret, timestamp + "." + body)). */
    public static String signature(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 재시도 대기 (1초, 5초, 15초, …). */
    public static long backoffMillis(int attempt) {
        return switch (attempt) {
            case 0 -> 1_000;
            case 1 -> 5_000;
            default -> 15_000L * (attempt - 1);
        };
    }
}
