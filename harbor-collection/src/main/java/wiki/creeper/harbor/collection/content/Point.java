package wiki.creeper.harbor.collection.content;

import java.util.List;
import java.util.Optional;
import wiki.creeper.harbor.common.yaml.YamlNode;

/** 좌표 하나. YAML {@code [x, y, z]}. */
public record Point(double x, double y, double z) {

    static Optional<Point> parse(YamlNode node) {
        List<YamlNode> values = node.asList();
        if (values.size() != 3) {
            node.error("좌표는 [x, y, z] 숫자 3개여야 합니다");
            return Optional.empty();
        }
        return Optional.of(
                new Point(
                        values.get(0).asDouble(),
                        values.get(1).asDouble(),
                        values.get(2).asDouble()));
    }
}
