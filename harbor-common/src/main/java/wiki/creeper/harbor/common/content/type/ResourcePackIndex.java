package wiki.creeper.harbor.common.content.type;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * resource_pack.yml: 배포 중인 리소스팩에 실제로 들어 있는 item_model·소리 목록 (추가 제안). 손으로 쓰지 않고 {@code harbor-tools
 * pack-index}로 리소스팩에서 만든다.
 *
 * <p>팩에 없는 모델을 아이템에 넣으면 보라·검정 상자로 깨지므로, 목록에 있는 모델만 쓴다. 파일이 없거나 목록이 비면 검사하지 않는다 (모든 모델을 있다고 본다).
 *
 * @param models {@code harbor:fish/harbor_mackerel} 형식의 item_model 키
 * @param sounds {@code harbor:fishing.cast} 형식의 소리 키
 */
public record ResourcePackIndex(Set<String> models, Set<String> sounds) {

    public static final ContentType.Single<ResourcePackIndex> TYPE =
            new ContentType.Single<>(
                    "resource_pack", "resource_pack.yml", false, ResourcePackIndex::parse);

    public ResourcePackIndex {
        models = Set.copyOf(models);
        sounds = Set.copyOf(sounds);
    }

    /** 목록이 있으면 그 안에 있는지, 목록이 없으면 true. */
    public boolean hasModel(String key) {
        return models.isEmpty() || models.contains(key);
    }

    public boolean hasSound(String key) {
        return sounds.isEmpty() || sounds.contains(key);
    }

    static ResourcePackIndex parse(YamlNode node, ParseContext ctx) {
        return new ResourcePackIndex(list(node, "models"), list(node, "sounds"));
    }

    private static Set<String> list(YamlNode node, String key) {
        List<String> values = node.get(key).map(YamlNode::asStringOrList).orElse(List.of());
        return new LinkedHashSet<>(values);
    }
}
