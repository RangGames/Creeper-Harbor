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
 * 읽기 전용 REST API (추가 제안). JDK HttpServer + 가상 스레드. 모든 요청은 {@code Authorization: Bearer <토큰>}이
 * 필요하고(GET /v1/health 제외), 토큰마다 분당 요청 수를 제한한다. 응답은 {@code {"ok":true,"data":…}} 또는 {@code
 * {"ok":false,"error":{"code","message"}}}.
 *
 * <p>HTTPS는 앞단 리버스 프록시에서 한다 (기본 bind 127.0.0.1). 경로 목록은 GET /v1.
 */
public final class ApiServer {

    /** 토큰 하나. */
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
                // URLDecoder가 잘못된 % 인코딩에 던진다: 연결을 끊지 않고 400으로 답한다
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
                write(ex, e.status(), error(e.code(), String.valueOf(e.getMessage())));
            } catch (RuntimeException e) {
                log.warn("REST {} 처리 실패: {}", path, String.valueOf(e));
                write(ex, 500, error("internal", "처리하지 못했습니다"));
            }
        }
    }

    /** 고정 시간 비교. */
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

    private static void write(HttpExchange ex, int status, Object body) throws IOException {
        byte[] bytes = Json.GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
