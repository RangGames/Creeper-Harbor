package wiki.creeper.harbor.collection.content;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.core.api.StatKey;

/** A single modifier (ADD). Used for collection and aquarium buffs. */
public record StatBonus(StatKey stat, double value) {

    static List<StatBonus> parseList(List<YamlNode> nodes) {
        List<StatBonus> result = new ArrayList<>();
        for (YamlNode n : nodes) {
            n.allowOnly(java.util.Set.of("stat", "value"));
            try {
                result.add(new StatBonus(StatKey.of(n.string("stat")), n.number("value")));
            } catch (IllegalArgumentException e) {
                n.error("stat 이름 형식이 틀렸습니다");
            }
        }
        return List.copyOf(result);
    }
}
