package wiki.creeper.harbor.shop.webhook;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Webhook intake (docs 20 12.4 steps 1~5). Separated from the HTTP server so it can be tested
 * without a server.
 *
 * <ol>
 *   <li>Reject bodies over 64KB (413)
 *   <li>Signature verification failure → 403 (warning log on the server side)
 *   <li>validation.webhook → ends with a {@code {"id": …}} response
 *   <li>INSERT IGNORE (webhook_id) into the inbox. If already present, 200 without processing
 *       (resend idempotency)
 *   <li>200 right after the inbox commit. InboxProcessor continues the processing
 * </ol>
 */
public final class WebhookIntake {

    public static final int MAX_BODY = 64 * 1024;

    /** One inbox row. */
    public record InboxRow(
            String webhookId, String type, @Nullable String txId, String bodySha256, String body) {}

    /** Inbox store. True if newly inserted, false if the same webhook_id already exists. */
    @FunctionalInterface
    public interface InboxStore {
        boolean insertIfAbsent(InboxRow row) throws Exception;
    }

    /** Response. */
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
            // Returning 500 makes Tebex resend
            return new Response(500, "", Outcome.STORE_FAILED);
        }
    }
}
