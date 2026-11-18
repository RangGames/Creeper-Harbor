package wiki.creeper.harbor.world.api;

import java.util.Set;
import java.util.UUID;

/**
 * 하우징 플롯 (housing_plot). harbor_homes 월드의 40×40 칸 하나 안에 크기 size의 정사각형 (가운데 정렬).
 *
 * @param plotId 나선 순서 번호
 * @param gridX 칸 좌표
 * @param minX 플롯 경계 (블록, 포함)
 * @param maxX 플롯 경계 (블록, 포함)
 * @param members 공동 건축자
 */
public record PlotInfo(
        long plotId,
        UUID owner,
        String ownerName,
        int gridX,
        int gridZ,
        int size,
        String theme,
        VisitPolicy visitPolicy,
        int likes,
        String world,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        double spawnX,
        double spawnY,
        double spawnZ,
        float spawnYaw,
        Set<UUID> members) {

    public PlotInfo {
        members = Set.copyOf(members);
    }

    public boolean contains(String worldName, double x, double z) {
        return world.equals(worldName) && x >= minX && x < maxX + 1 && z >= minZ && z < maxZ + 1;
    }

    public boolean isMember(UUID player) {
        return owner.equals(player) || members.contains(player);
    }
}
