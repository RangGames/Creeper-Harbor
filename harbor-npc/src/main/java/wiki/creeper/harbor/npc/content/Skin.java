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
 * Mannequin 스킨. 다음 중 하나로 적는다 (추가 제안: 닉네임·URL·해시).
 *
 * <pre>{@code
 * skin: harbor:entity/npc/hansol                    # 리소스팩 텍스처 키 (skinPatch body, 기본 방식)
 * skin: Notch                                       # 플레이어 닉네임 (서버가 Mojang에서 스킨을 받아 온다)
 * skin: http://textures.minecraft.net/texture/<해시> # 스킨 URL (textures.minecraft.net만 클라이언트가 받는다)
 * skin: {texture: harbor:entity/npc/iseul, model: slim}
 * skin: {player: Notch}
 * skin: {url: "http://textures.minecraft.net/texture/<해시>", model: slim}
 * skin: {hash: <64자리 16진수>}                        # 위 URL의 해시만
 * skin: {value: <base64>, signature: <서명>}          # textures 속성 (mineskin 등에서 받은 값, 서명은 없어도 된다)
 * }</pre>
 *
 * <p>model(wide · slim)을 적지 않으면 리소스팩 텍스처는 wide, 나머지는 스킨 원래 모델을 쓴다.
 *
 * @param texture 리소스팩 텍스처 키
 * @param slim 얇은 팔 모델 (texture·url에 적용)
 * @param value textures 속성 값 (url·hash도 이 값으로 바꿔 둔다)
 * @param signature textures 속성 서명 (없으면 서명 없는 속성)
 * @param player 플레이어 닉네임
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

    /** 짧은 형식: 리소스팩 키 · URL · 닉네임을 모양으로 가린다. */
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
            // 클라이언트는 http 주소로 받는다 (Mojang textures 속성과 같은 꼴)
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

    /** 서명 없는 textures 속성 값 (스킨 URL만 담는다, 플레이어 머리 아이템과 같은 방식). */
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
