package wiki.creeper.harbor.shop.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Webhook signature verification (docs 20 12.4 step 2). The signature format is a legal/technical
 * review item to recheck against the latest Tebex docs at Phase 3 start, so it is kept swappable
 * (config.yml webhook.signature).
 *
 * <ul>
 *   <li>{@code tebex_v1}: X-Signature = hex(HMAC-SHA256(secret, hex(SHA-256(body)))) (per the docs,
 *       default)
 *   <li>{@code hmac_body}: X-Signature = hex(HMAC-SHA256(secret, body))
 * </ul>
 *
 * Comparison is constant-time via {@link MessageDigest#isEqual}.
 */
@FunctionalInterface
public interface SignatureVerifier {

    boolean verify(byte[] body, @Nullable String header);

    /**
     * Creates one by config name. For an unknown name or an empty secret, returns a verifier that
     * rejects everything.
     */
    static SignatureVerifier create(String mode, String secret) {
        if (secret.isBlank()) {
            return (body, header) -> false;
        }
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        return switch (mode.toLowerCase(Locale.ROOT)) {
            case "tebex_v1" -> (body, header) -> matches(hmac(key, sha256Hex(body)), header);
            case "hmac_body" -> (body, header) -> matches(hmac(key, body), header);
            default -> (body, header) -> false;
        };
    }

    static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 없음", e);
        }
    }

    static byte[] hmac(byte[] key, String text) {
        return hmac(key, text.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 실패", e);
        }
    }

    private static boolean matches(byte[] expected, @Nullable String header) {
        if (header == null || header.isBlank()) {
            return false;
        }
        String expectedHex = HexFormat.of().formatHex(expected);
        return MessageDigest.isEqual(
                expectedHex.getBytes(StandardCharsets.US_ASCII),
                header.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
}
