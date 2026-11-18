package wiki.creeper.harbor.npc.content;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Mannequin 스킨. 리소스팩 텍스처 키(skinPatch body, 예: {@code harbor:entity/npc/hansol})가 기본이고, 서명된 텍스처
 * 값(Mojang textures 속성)도 쓸 수 있다.
 *
 * <pre>{@code
 * skin: harbor:entity/npc/hansol                    # 짧은 형식
 * skin: {texture: harbor:entity/npc/isle, model: slim}
 * skin: {value: <base64>, signature: <서명>}
 * }</pre>
 *
 * @param texture 리소스팩 텍스처 키
 * @param slim 얇은 팔 모델
 */
public record Skin(
        @Nullable String texture,
        boolean slim,
        @Nullable String value,
        @Nullable String signature) {

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public static Optional<Skin> parse(YamlNode node) {
        if (node.isScalar()) {
            String key = node.asString();
            if (!KEY.matcher(key).matches()) {
                node.error("스킨은 리소스팩 텍스처 키(네임스페이스:경로)여야 합니다: '" + key + "'");
                return Optional.empty();
            }
            return Optional.of(new Skin(key, false, null, null));
        }
        node.allowOnly(Set.of("texture", "model", "value", "signature"));
        String texture = node.optString("texture").orElse(null);
        if (texture != null && !KEY.matcher(texture).matches()) {
            node.error("texture는 리소스팩 텍스처 키(네임스페이스:경로)여야 합니다: '" + texture + "'");
        }
        String model = node.string("model", "wide");
        if (!model.equals("wide") && !model.equals("slim")) {
            node.error("model은 wide 또는 slim이어야 합니다: '" + model + "'");
        }
        String value = node.optString("value").orElse(null);
        if (texture == null && value == null) {
            node.error("스킨에는 texture 또는 value가 있어야 합니다");
        }
        return Optional.of(
                new Skin(
                        texture,
                        model.equals("slim"),
                        value,
                        node.optString("signature").orElse(null)));
    }
}
