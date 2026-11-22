package wiki.creeper.harbor.common.content.type;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * resource_pack.yml: the item_model and sound lists actually contained in the deployed resource
 * pack (proposed). Not written by hand; generated from the resource pack with {@code harbor-tools
 * pack-index}.
 *
 * <p>An item with a model missing from the pack renders as a broken purple-black box, so only
 * listed models are used. If the file is missing or the list is empty, nothing is checked (all
 * models are assumed present).
 *
 * @param models item_model keys like {@code harbor:fish/harbor_mackerel}
 * @param sounds sound keys like {@code harbor:fishing.cast}
 */
public record ResourcePackIndex(Set<String> models, Set<String> sounds) {

    public static final ContentType.Single<ResourcePackIndex> TYPE =
            new ContentType.Single<>(
                    "resource_pack", "resource_pack.yml", false, ResourcePackIndex::parse);

    public ResourcePackIndex {
        models = Set.copyOf(models);
        sounds = Set.copyOf(sounds);
    }

    /** If there is a list, whether it is in it; if there is no list, true. */
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
