package wiki.creeper.harbor.web.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.util.Json;

/**
 * Read-only REST API (proposed). JDK HttpServer + virtual threads. Every request needs {@code
 * Authorization: Bearer <token>} (except GET /v1/health), and requests per minute are limited per
 * token. Responses are {@code {"ok":true,"data":…}} or {@code
 * {"ok":false,"error":{"code","message"[,"param","allowed"]}}}. Every field name is snake_case.
 *
 * <p>HTTPS is terminated at a front reverse proxy (default bind 127.0.0.1). The route list is at
 * GET /v1.
 */
public final class ApiServer {

    /** One token. */
    public record Token(String name, String token) {}

    private final Router router;
    private final List<Token> tokens;
    private final RateLimiter limiter;
    private final List<String> corsOrigins;
    private final Logger log;
    private @Nullable HttpServer server;
    private @Nullable ExecutorService executor;

    public ApiServer(
            Router router,
            List<Token> tokens,
            int ratePerMinute,
            List<String> corsOrigins,
            Logger log) {
        this.router = router;
        this.tokens = List.copyOf(tokens);
        this.limiter = new RateLimiter(ratePerMinute);
        this.corsOrigins = List.copyOf(corsOrigins);
        this.log = log;
    }

    public void start(String bind, int port) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(bind, port), 0);
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        s.setExecutor(ex);
        s.createContext("/v1", this::handle);
        s.start();
        server = s;
        executor = ex;
        if (tokens.isEmpty()) {
            log.warn("REST API에 토큰이 없어 /v1/health 말고는 모두 거절합니다 (config.yml http.tokens)");
        }
    }

    public void stop() {
        HttpServer s = server;
        if (s != null) {
            s.stop(1);
        }
        ExecutorService ex = executor;
        if (ex != null) {
            ex.shutdownNow();
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            cors(ex);
            String method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            String path = ex.getRequestURI().getPath().substring("/v1".length());
            if (path.equals("/health") || path.equals("/health/")) {
                write(ex, 200, ok(Map.of("status", "up")));
                return;
            }
            Optional<Token> token = auth(ex.getRequestHeaders().getFirst("Authorization"));
            if (token.isEmpty()) {
                write(ex, 401, error("unauthorized", "Authorization: Bearer <token>이 필요합니다"));
                return;
            }
            if (!limiter.allow(token.get().name(), System.currentTimeMillis())) {
                write(ex, 429, error("rate_limited", "요청이 너무 많습니다"));
                return;
            }
            if (!method.equals("GET")) {
                write(ex, 405, error("method_not_allowed", "GET만 받습니다"));
                return;
            }
            if (path.isEmpty() || path.equals("/")) {
                write(ex, 200, ok(router.list()));
                return;
            }
            Optional<Map.Entry<Router.Handler, Map<String, String>>> match;
            Map<String, String> query;
            try {
                match = router.match(path);
                String rawQuery = ex.getRequestURI().getRawQuery();
                query = Router.query(rawQuery == null ? "" : rawQuery);
            } catch (IllegalArgumentException e) {
                // URLDecoder throws on malformed % encoding: answer 400 instead of dropping the
                // connection
                write(ex, 400, error("bad_request", "경로·쿼리 인코딩이 잘못되었습니다"));
                return;
            }
            if (match.isEmpty()) {
                write(ex, 404, error("not_found", "없는 경로입니다: " + path));
                return;
            }
            Router.Request request = new Router.Request(match.get().getValue(), query);
            try {
                write(ex, 200, ok(match.get().getKey().handle(request)));
            } catch (Router.ApiException e) {
                write(ex, e.status(), error(e));
            } catch (RuntimeException e) {
                log.warn("REST {} 처리 실패: {}", path, String.valueOf(e));
                write(ex, 500, error("internal", "처리하지 못했습니다"));
            }
        }
    }

    /** Constant-time comparison. */
    Optional<Token> auth(@Nullable String header) {
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        byte[] given =
                header.substring("Bearer ".length()).strip().getBytes(StandardCharsets.UTF_8);
        Token found = null;
        for (Token t : tokens) {
            if (!t.token().isEmpty()
                    && MessageDigest.isEqual(given, t.token().getBytes(StandardCharsets.UTF_8))) {
                found = t;
            }
        }
        return Optional.ofNullable(found);
    }

    private void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && corsOrigins.contains(origin)) {
            var h = ex.getResponseHeaders();
            h.set("Access-Control-Allow-Origin", origin);
            h.set("Vary", "Origin");
            h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
            h.set("Access-Control-Allow-Methods", "GET, OPTIONS");
        }
    }

    private static Map<String, Object> ok(@Nullable Object data) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("data", data);
        return out;
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("ok", false, "error", Map.of("code", code, "message", message));
    }

    /** Error envelope with param (and allowed, for choice parameters) when present. */
    static Map<String, Object> error(Router.ApiException e) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("code", e.code());
        err.put("message", String.valueOf(e.getMessage()));
        if (!e.param().isEmpty()) {
            err.put("param", e.param());
        }
        if (!e.allowed().isEmpty()) {
            err.put("allowed", e.allowed());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", err);
        return out;
    }

    private static void write(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
