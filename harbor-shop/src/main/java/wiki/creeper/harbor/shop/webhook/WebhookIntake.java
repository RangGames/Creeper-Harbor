package wiki.creeper.harbor.shop.webhook;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 웹훅 수신 처리 (docs 20 12.4 1~5단계). HTTP 서버와 분리해 서버 없이 테스트한다.
 *
 * <ol>
 *   <li>본문 64KB 초과 거부 (413)
 *   <li>서명 검증 실패 → 403 (경고 로그는 서버 쪽)
 *   <li>validation.webhook → {@code {"id": …}} 응답으로 끝
 *   <li>수신함에 INSERT IGNORE(webhook_id). 이미 있으면 처리하지 않고 200 (재전송 멱등)
 *   <li>수신함 커밋 직후 200. 처리는 InboxProcessor가 이어서 한다
 * </ol>
 */
public final class WebhookIntake {

    public static final int MAX_BODY = 64 * 1024;

    /** 수신함 한 행. */
    public record InboxRow(
            String webhookId, String type, @Nullable String txId, String bodySha256, String body) {}

    /** 수신함 저장소. 새로 넣었으면 true, 같은 webhook_id가 이미 있으면 false. */
    @FunctionalInterface
    public interface InboxStore {
        boolean insertIfAbsent(InboxRow row) throws Exception;
    }

    /** 응답. */
    public enum Outcome {
        TOO_LARGE,
        BAD_SIGNATURE,
        MALFORMED,
        VALIDATION,
        ACCEPTED,
        DUPLICATE,
        STORE_FAILED
    }

    public record Response(int status, String body, Outcome outcome) {}

    private final SignatureVerifier verifier;
    private final InboxStore store;

    public WebhookIntake(SignatureVerifier verifier, InboxStore store) {
        this.verifier = verifier;
        this.store = store;
    }

    public Response handle(byte[] body, @Nullable String signature) {
        if (body.length > MAX_BODY) {
            return new Response(413, "", Outcome.TOO_LARGE);
        }
        if (!verifier.verify(body, signature)) {
            return new Response(403, "", Outcome.BAD_SIGNATURE);
        }
        String text = new String(body, StandardCharsets.UTF_8);
        Optional<WebhookEvent> parsed = WebhookEvent.parse(text);
        if (parsed.isEmpty()) {
            return new Response(400, "", Outcome.MALFORMED);
        }
        WebhookEvent event = parsed.get();
        if (WebhookEvent.VALIDATION.equals(event.type())) {
            return new Response(
                    200,
                    "{\"id\":\"" + event.id().replace("\\", "\\\\").replace("\"", "\\\"") + "\"}",
                    Outcome.VALIDATION);
        }
        InboxRow row =
                new InboxRow(
                        event.id(),
                        event.type(),
                        event.txId(),
                        SignatureVerifier.sha256Hex(body),
                        text);
        try {
            return store.insertIfAbsent(row)
                    ? new Response(200, "", Outcome.ACCEPTED)
                    : new Response(200, "", Outcome.DUPLICATE);
        } catch (Exception e) {
            // 500을 돌려주면 Tebex가 다시 보낸다
            return new Response(500, "", Outcome.STORE_FAILED);
        }
    }
}
