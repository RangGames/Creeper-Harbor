package wiki.creeper.harbor.shop.webhook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Tebex 웹훅 HTTP 수신기 (docs 20 12.4 '실행 위치'). JDK 내장 HttpServer + 가상 스레드 실행기, 추가 라이브러리 없음. 게임 포트와 분리된
 * 내부 포트에만 묶고, 앞단 리버스 프록시가 TLS와 Tebex IP 제한을 맡는다. 응답 목표: 수신함 커밋 후 200까지 300ms 이하.
 */
public final class WebhookServer {

    private final WebhookIntake intake;
    private final Runnable onAccepted;
    private final List<String> allowedIps;
    private final Logger log;
    private @Nullable HttpServer server;
    private @Nullable ExecutorService executor;

    public WebhookServer(
            WebhookIntake intake, Runnable onAccepted, List<String> allowedIps, Logger log) {
        this.intake = intake;
        this.onAccepted = onAccepted;
        this.allowedIps = List.copyOf(allowedIps);
        this.log = log;
    }

    public void start(String bind, int port, String path) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(bind, port), 64);
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(exec);
        http.createContext(path, this::handle);
        http.start();
        server = http;
        executor = exec;
        log.info("Tebex 웹훅 수신기: http://{}:{}{}", bind, port, path);
    }

    public void stop() {
        HttpServer http = server;
        if (http != null) {
            http.stop(1);
        }
        ExecutorService exec = executor;
        if (exec != null) {
            exec.shutdownNow();
        }
        server = null;
        executor = null;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "");
                return;
            }
            String remote = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!allowedIps.isEmpty() && !allowedIps.contains(remote)) {
                log.warn("허용되지 않은 IP의 웹훅 요청: {}", remote);
                respond(exchange, 403, "");
                return;
            }
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readNBytes(WebhookIntake.MAX_BODY + 1);
            }
            WebhookIntake.Response response =
                    intake.handle(body, exchange.getRequestHeaders().getFirst("X-Signature"));
            switch (response.outcome()) {
                case BAD_SIGNATURE -> log.warn("웹훅 서명 검증 실패 ({})", remote);
                case STORE_FAILED -> log.error("웹훅 수신함 저장 실패");
                case ACCEPTED -> onAccepted.run();
                default -> {}
            }
            respond(exchange, response.status(), response.body());
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
