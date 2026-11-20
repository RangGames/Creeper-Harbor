package wiki.creeper.harbor.web.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** GET 경로 고르기 ("/players/{id}/codex" 꼴, 서버 API 없음). */
public final class Router {

    /** 요청 정보. */
    public record Request(Map<String, String> params, Map<String, String> query) {

        public String param(String name) {
            return params.getOrDefault(name, "");
        }

        public String query(String name, String fallback) {
            String v = query.get(name);
            return v == null || v.isEmpty() ? fallback : v;
        }

        public int queryInt(String name, int fallback, int min, int max) {
            try {
                return Math.clamp(
                        Integer.parseInt(query(name, String.valueOf(fallback))), min, max);
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    /** 처리기: 응답 data를 돌려준다 (Gson이 JSON으로). */
    @FunctionalInterface
    public interface Handler {
        Object handle(Request request) throws ApiException;
    }

    /** 오류 응답. */
    public static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String code;

        public ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private record Route(List<String> parts, Handler handler, String description) {}

    /** 경로 목록 (GET /v1 안내). */
    public record RouteInfo(String path, String description) {}

    private static final java.util.regex.Pattern SLASH = java.util.regex.Pattern.compile("/");
    private static final java.util.regex.Pattern AMP = java.util.regex.Pattern.compile("&");

    private final List<Route> routes = new ArrayList<>();

    public void get(String path, String description, Handler handler) {
        routes.add(new Route(split(path), handler, description));
    }

    public List<RouteInfo> list() {
        return routes.stream()
                .map(r -> new RouteInfo("/v1/" + String.join("/", r.parts()), r.description()))
                .toList();
    }

    /** 맞는 경로 (경로 변수 포함). */
    public Optional<Map.Entry<Handler, Map<String, String>>> match(String path) {
        List<String> parts = split(path);
        for (Route r : routes) {
            if (r.parts().size() != parts.size()) {
                continue;
            }
            Map<String, String> params = new HashMap<>();
            boolean ok = true;
            for (int i = 0; i < parts.size() && ok; i++) {
                String p = r.parts().get(i);
                if (p.startsWith("{") && p.endsWith("}")) {
                    params.put(p.substring(1, p.length() - 1), decode(parts.get(i)));
                } else if (!p.equals(parts.get(i))) {
                    ok = false;
                }
            }
            if (ok) {
                return Optional.of(Map.entry(r.handler(), Map.copyOf(params)));
            }
        }
        return Optional.empty();
    }

    private static List<String> split(String path) {
        return SLASH.splitAsStream(path).filter(s -> !s.isEmpty()).toList();
    }

    /** a=1&b=%20x → {a: 1, b: " x"}. */
    public static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw.isEmpty()) {
            return out;
        }
        for (String pair : AMP.splitAsStream(raw).toList()) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(decode(k), decode(v));
        }
        return out;
    }

    static String decode(String s) {
        return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
