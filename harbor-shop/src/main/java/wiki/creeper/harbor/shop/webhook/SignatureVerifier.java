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
 * 웹훅 서명 검증 (docs 20 12.4 2단계). 서명 형식은 Phase 3 착수 때 Tebex 최신 문서로 재확인할 법률·기술 검토 항목이라 바꿔 끼울 수 있게 둔다
 * (config.yml webhook.signature).
 *
 * <ul>
 *   <li>{@code tebex_v1}: X-Signature = hex(HMAC-SHA256(비밀키, hex(SHA-256(본문)))) (문서 기준, 기본값)
 *   <li>{@code hmac_body}: X-Signature = hex(HMAC-SHA256(비밀키, 본문))
 * </ul>
 *
 * 비교는 {@link MessageDigest#isEqual}로 상수 시간에 한다.
 */
@FunctionalInterface
public interface SignatureVerifier {

    boolean verify(byte[] body, @Nullable String header);

    /** 설정 이름으로 만든다. 모르는 이름이거나 비밀키가 비면 모두 거부하는 검증기를 돌려준다. */
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
