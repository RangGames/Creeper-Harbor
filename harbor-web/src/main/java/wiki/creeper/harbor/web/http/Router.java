package wiki.creeper.harbor.web.http;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** GET route matching ("/players/{id}/collection" style, no server API). */
public final class Router {

    /** Request info. */
    public record Request(Map<String, String> params, Map<String, String> query) {

        public String param(String name) {
            return params.getOrDefault(name, "");
        }

        public String query(String name, String fallback) {
            String v = query.get(name);
            return v == null || v.isEmpty() ? fallback : v;
        }

        /**
         * Integer query parameter. Missing or empty means fallback; a number outside min..max is
         * clamped; anything that is not a base-10 integer is 400 bad_parameter.
         */
        public long queryLong(String name, long fallback, long min, long max) throws ApiException {
            String raw = query.get(name);
            if (raw == null || raw.isEmpty()) {
                return fallback;
            }
            try {
                return Math.clamp(Long.parseLong(raw.strip()), min, max);
            } catch (NumberFormatException e) {
                throw ApiException.badParameter(
                        name, name + "는 정수여야 합니다 (" + min + "~" + max + "): " + raw, List.of());
            }
        }

        /** {@link #queryLong} for int ranges. */
        public int queryInt(String name, int fallback, int min, int max) throws ApiException {
            return (int) queryLong(name, fallback, min, max);
        }

        /**
         * Choice query parameter (exact, lowercase). Missing or empty means fallback; any other
         * value not in allowed is 400 bad_parameter listing the allowed values.
         */
        public String queryChoice(String name, String fallback, List<String> allowed)
                throws ApiException {
            String v = query(name, fallback);
            if (!allowed.contains(v)) {
                throw ApiException.badParameter(
                        name,
                        name + "는 " + String.join(", ", allowed) + " 중 하나여야 합니다: " + v,
                        allowed);
            }
            return v;
        }
    }

    /** Handler: returns the response data (serialized to JSON by Gson). */
    @FunctionalInterface
    public interface Handler {
        Object handle(Request request) throws ApiException;
    }

    /** Error response. */
    public static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String code;
        private final String param;
        private final List<String> allowed;

        public ApiException(int status, String code, String message) {
            this(status, code, message, "", List.of());
        }

        private ApiException(
                int status, String code, String message, String param, List<String> allowed) {
            super(message);
            this.status = status;
            this.code = code;
            this.param = param;
            this.allowed = List.copyOf(allowed);
        }

        /** 400 bad_parameter for a query parameter (allowed: choices, empty if not a choice). */
        public static ApiException badParameter(
                String param, String message, List<String> allowed) {
            return new ApiException(400, "bad_parameter", message, param, allowed);
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        /** Offending query parameter name (empty if not a parameter error). */
        public String param() {
            return param;
        }

        /** Allowed values of a choice parameter (empty otherwise). */
        public List<String> allowed() {
            return allowed;
        }
    }

    private record Route(List<String> parts, Handler handler, String description) {}

    /** Route list (GET /v1 index). */
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

    /** Matching route (with path variables). */
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
