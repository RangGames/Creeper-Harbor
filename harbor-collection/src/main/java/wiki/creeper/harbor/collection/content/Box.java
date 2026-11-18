package wiki.creeper.harbor.collection.content;

import java.util.List;
import wiki.creeper.harbor.common.yaml.YamlNode;

/** 블록 좌표 상자 (양 끝 포함). YAML {@code [x1, y1, z1, x2, y2, z2]}. 서버 API를 쓰지 않는다. */
public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public static final Box EMPTY = new Box(0, 0, 0, -1, -1, -1);

    public static Box of(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Box(
                Math.min(x1, x2),
                Math.min(y1, y2),
                Math.min(z1, z2),
                Math.max(x1, x2),
                Math.max(y1, y2),
                Math.max(z1, z2));
    }

    public boolean isEmpty() {
        return maxX < minX;
    }

    /** 블록 좌표 상자라 끝 블록 전체(+1)까지 포함한다. */
    public boolean contains(double x, double y, double z) {
        return !isEmpty()
                && x >= minX
                && x < maxX + 1
                && y >= minY
                && y < maxY + 1
                && z >= minZ
                && z < maxZ + 1;
    }

    public double centerX() {
        return (minX + maxX + 1) / 2;
    }

    public double centerY() {
        return (minY + maxY + 1) / 2;
    }

    public double centerZ() {
        return (minZ + maxZ + 1) / 2;
    }

    /** 상자 안 점 (0~1 비율, margin 블록만큼 안쪽). */
    public Point lerp(double fx, double fy, double fz, double margin) {
        double sx = Math.max(0, maxX + 1 - minX - 2 * margin);
        double sy = Math.max(0, maxY + 1 - minY - 2 * margin);
        double sz = Math.max(0, maxZ + 1 - minZ - 2 * margin);
        return new Point(minX + margin + sx * fx, minY + margin + sy * fy, minZ + margin + sz * fz);
    }

    static Box parse(YamlNode node) {
        List<YamlNode> values = node.asList();
        if (values.size() != 6) {
            node.error("상자는 [x1, y1, z1, x2, y2, z2] 숫자 6개여야 합니다");
            return EMPTY;
        }
        return of(
                values.get(0).asDouble(),
                values.get(1).asDouble(),
                values.get(2).asDouble(),
                values.get(3).asDouble(),
                values.get(4).asDouble(),
                values.get(5).asDouble());
    }
}
