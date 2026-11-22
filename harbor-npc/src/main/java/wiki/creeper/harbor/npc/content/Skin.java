package wiki.creeper.harbor.npc.content;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Mannequin skin. Written in one of the following forms (proposed: nickname, URL, hash).
 *
 * <pre>{@code
 * skin: harbor:entity/npc/hansol                    # resource pack texture key (skinPatch body, default)
 * skin: Notch                                       # player nickname (the server fetches the skin from Mojang)
 * skin: http://textures.minecraft.net/texture/<hash> # skin URL (clients only fetch from textures.minecraft.net)
 * skin: {texture: harbor:entity/npc/iseul, model: slim}
 * skin: {player: Notch}
 * skin: {url: "http://textures.minecraft.net/texture/<hash>", model: slim}
 * skin: {hash: <64 hex digits>}                      # just the hash of the URL above
 * skin: {value: <base64>, signature: <signature>}    # textures property (e.g. from mineskin; signature optional)
 * }</pre>
 *
 * <p>If model (wide · slim) is not given, resource pack textures use wide and the rest use the
 * skin's original model.
 *
 * @param texture resource pack texture key
 * @param slim slim-arm model (applies to texture and url)
 * @param value textures property value (url and hash are converted to this too)
 * @param signature textures property signature (unsigned property if absent)
 * @param player player nickname
 */
public record Skin(
        @Nullable String texture,
        boolean slim,
        @Nullable String value,
        @Nullable String signature,
        @Nullable String player) {

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{32,64}");
    private static final String TEXTURE_HOST = "textures.minecraft.net";

    public Skin(
            @Nullable String texture,
            boolean slim,
            @Nullable String value,
            @Nullable String signature) {
        this(texture, slim, value, signature, null);
    }

    public static Optional<Skin> parse(YamlNode node) {
        if (node.isScalar()) {
            return scalar(node, node.asString().strip());
        }
        node.allowOnly(Set.of("texture", "player", "url", "hash", "model", "value", "signature"));
        String texture = node.optString("texture").orElse(null);
        String player = node.optString("player").orElse(null);
        String url = node.optString("url").orElse(null);
        String hash = node.optString("hash").orElse(null);
        String value = node.optString("value").orElse(null);
        int sources =
                (texture != null ? 1 : 0)
                        + (player != null ? 1 : 0)
                        + (url != null ? 1 : 0)
                        + (hash != null ? 1 : 0)
                        + (value != null ? 1 : 0);
        if (sources == 0) {
            node.error("스킨에는 texture · player · url · hash · value 중 하나가 있어야 합니다");
            return Optional.empty();
        }
        if (sources > 1) {
            node.error("스킨은 texture · player · url · hash · value 중 하나만 적습니다");
            return Optional.empty();
        }
        String model = node.optString("model").orElse(null);
        if (model != null && !model.equals("wide") && !model.equals("slim")) {
            node.error("model은 wide 또는 slim이어야 합니다: '" + model + "'");
        }
        boolean slim = "slim".equals(model);
        if (texture != null) {
            if (!KEY.matcher(texture).matches()) {
                node.error("texture는 리소스팩 텍스처 키(네임스페이스:경로)여야 합니다: '" + texture + "'");
                return Optional.empty();
            }
            return Optional.of(new Skin(texture, slim, null, null, null));
        }
        if (player != null) {
            if (!NAME.matcher(player).matches()) {
                node.error("player는 마인크래프트 닉네임(영문·숫자·밑줄 3~16자)이어야 합니다: '" + player + "'");
                return Optional.empty();
            }
            return Optional.of(new Skin(null, false, null, null, player));
        }
        if (url != null || hash != null) {
            Optional<String> skinUrl = url != null ? checkUrl(node, url) : hashUrl(node, hash);
            return skinUrl.map(u -> new Skin(null, slim, texturesValue(u, slim), null, null));
        }
        return Optional.of(
                new Skin(null, slim, value, node.optString("signature").orElse(null), null));
    }

    /** Short form: tells resource pack key, URL and nickname apart by shape. */
    private static Optional<Skin> scalar(YamlNode node, String raw) {
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return checkUrl(node, raw)
                    .map(u -> new Skin(null, false, texturesValue(u, false), null));
        }
        if (KEY.matcher(raw).matches()) {
            return Optional.of(new Skin(raw, false, null, null, null));
        }
        if (NAME.matcher(raw).matches()) {
            return Optional.of(new Skin(null, false, null, null, raw));
        }
        node.error("스킨은 리소스팩 텍스처 키(네임스페이스:경로) · 플레이어 닉네임 · 스킨 URL 중 하나여야 합니다: '" + raw + "'");
        return Optional.empty();
    }

    private static Optional<String> checkUrl(YamlNode node, String url) {
        try {
            URI uri = URI.create(url);
            if (!TEXTURE_HOST.equalsIgnoreCase(String.valueOf(uri.getHost()))) {
                node.error("스킨 URL은 " + TEXTURE_HOST + " 주소여야 합니다 (클라이언트가 다른 주소는 받지 않음): " + url);
                return Optional.empty();
            }
            // Clients fetch via the http address (same shape as the Mojang textures property)
            return Optional.of("http://" + TEXTURE_HOST + uri.getPath());
        } catch (IllegalArgumentException e) {
            node.error("스킨 URL 형식이 틀렸습니다: " + url);
            return Optional.empty();
        }
    }

    private static Optional<String> hashUrl(YamlNode node, @Nullable String hash) {
        String h = String.valueOf(hash).toLowerCase(Locale.ROOT);
        if (!HASH.matcher(h).matches()) {
            node.error("hash는 스킨 URL 끝의 16진수(32~64자리)여야 합니다: '" + hash + "'");
            return Optional.empty();
        }
        return Optional.of("http://" + TEXTURE_HOST + "/texture/" + h);
    }

    /** Unsigned textures property value (holds only the skin URL, same as player head items). */
    static String texturesValue(String url, boolean slim) {
        String json =
                "{\"textures\":{\"SKIN\":{\"url\":\""
                        + url
                        + "\""
                        + (slim ? ",\"metadata\":{\"model\":\"slim\"}" : "")
                        + "}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
