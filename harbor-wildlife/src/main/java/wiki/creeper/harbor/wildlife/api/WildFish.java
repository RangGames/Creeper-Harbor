package wiki.creeper.harbor.wildlife.api;

import java.util.UUID;
import org.bukkit.Location;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 야생 개체 하나의 읽기 전용 모습 (마지막 계산 주기 기준, 최대 100ms 늦음). 개체는 엔티티가 아니라 계산 스레드의 가상 개체이고, 보이는 동안만 Core 리그로
 * 그린다.
 *
 * @param id 개체 ID (리그·엔티티 UUID와 다름)
 * @param species wildlife/species.yml 항목 ID
 * @param school 무리 ID (혼자면 null)
 * @param radius 몸 반지름 (블록, raycast 판정용)
 * @param visible 지금 누군가에게 보이는지
 * @param scripted spawnScripted·spawnSchool로 만든 개체인지
 */
public record WildFish(
        UUID id,
        FishId fish,
        HarborId species,
        Location location,
        WildState state,
        Medium medium,
        @Nullable UUID school,
        double radius,
        boolean visible,
        boolean scripted) {

    public WildFish {
        location = location.clone();
    }

    @Override
    public Location location() {
        return location.clone();
    }
}
