package wiki.creeper.harbor.core.api;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.RigDef;

/**
 * 디스플레이 리그 재생 ('16. 플러그인 설계' 5.10). rigs/*.yml 리그를 Display 엔티티 묶음으로 띄우고 애니메이션을 재생한다. 쓰는 곳: Fishing
 * 물고기 그림자·점프, Collection 수족관·개인 어항, Wildlife 야생 물고기, Boss 보스 모델(부위별 Interaction 판정), Shop 펫·선박 장식.
 *
 * <p>표시 방식은 저장되지 않는 실제 엔티티다 (Phase 1 결정 전 기본안). 개인 리그는 기본으로 숨기고 시청자에게만 보여준다 (Paper 엔티티 숨김·보이기 API).
 * 엔티티는 청크에 저장되지 않고, 청크가 언로드되거나 Core가 꺼지면 사라진다.
 *
 * <p>스레드: 모든 메서드는 메인 스레드 전용이다 (리그 정의 조회 {@link #definition}만 어디서나).
 */
public interface RigService {

    /** 콘텐츠에 있는 리그 ID. */
    Set<RigId> ids();

    Optional<RigDef> definition(RigId id);

    /**
     * 리그를 띄운다. 위치의 yaw·pitch는 진행 방향으로 쓴다 ({@link RigInstance#setHeading}).
     *
     * @throws IllegalArgumentException 리그가 콘텐츠에 없을 때
     * @throws IllegalStateException 메인 스레드가 아닐 때
     */
    RigInstance spawn(RigId rig, Location at, RigOptions options);

    default RigInstance spawn(RigId rig, Location at) {
        return spawn(rig, at, RigOptions.publicRig());
    }

    /** 살아 있는 리그 전체. */
    Collection<? extends RigInstance> instances();

    /** 리그를 이루는 엔티티(루트·부품·판정 상자)로 리그를 찾는다. */
    Optional<RigInstance> byEntity(Entity entity);

    /** 이 플레이어가 지금 보고 있는 리그 부품 수 (플레이어 주변 300개 기준 확인용, '16. 플러그인 설계' 7장). */
    int visibleParts(Player player);
}
