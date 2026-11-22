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
 * Tebex webhook HTTP receiver (docs 20 12.4 'where it runs'). JDK built-in HttpServer + virtual
 * thread executor, no extra libraries. Bound only to an internal port separate from the game port;
 * the front reverse proxy handles TLS and Tebex IP restrictions. Response target: 300 ms or less
 * until 200 after the inbox commit.
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
