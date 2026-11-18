package wiki.creeper.harbor.fishing.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.StatKey;

/** 보정자 하나 (요리 버프 등). op 기본 ADD. */
public record Effect(StatKey stat, Modifier.Op op, double value) {

    static List<Effect> parseList(List<YamlNode> nodes) {
        List<Effect> result = new ArrayList<>();
        for (YamlNode n : nodes) {
            n.allowOnly(Set.of("modifier", "value", "op"));
            Modifier.Op op =
                    switch (n.string("op", "add")) {
                        case "add" -> Modifier.Op.ADD;
                        case "multiply" -> Modifier.Op.MULTIPLY;
                        default -> {
                            n.error("op는 add 또는 multiply여야 합니다");
                            yield Modifier.Op.ADD;
                        }
                    };
            try {
                result.add(new Effect(StatKey.of(n.string("modifier")), op, n.number("value")));
            } catch (IllegalArgumentException e) {
                n.error("modifier 이름 형식이 틀렸습니다 (소문자·숫자·밑줄)");
            }
        }
        return List.copyOf(result);
    }
}
