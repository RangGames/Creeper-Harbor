package wiki.creeper.harbor.core.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RigId;

/**
 * 띄운 리그 하나 ('16. 플러그인 설계' 5.10 RigInstance). 루트(아이템 없는 ItemDisplay) 하나에 부품 Display들이 승객으로 타고, 이동은
 * 루트 텔레포트 한 번(teleport_duration 보간)으로 한다. 애니메이션은 키 프레임에서만 다음 키 값으로 보간 명령을 보낸다.
 *
 * <p>메인 스레드 전용. 제거된 뒤의 호출은 무시된다.
 */
public interface RigInstance {

    /** 제거 사유. */
    enum RemoveReason {
        /** {@link #remove()} 호출. */
        REQUESTED,
        /** 엔티티가 있던 청크가 언로드됨. */
        CHUNK_UNLOAD,
        /** 루트 엔티티가 다른 이유로 사라짐. */
        ENTITY_GONE,
        /** Core 종료. */
        SHUTDOWN
    }

    /** 이 리그 인스턴스의 ID (엔티티 UUID와 다름). */
    UUID id();

    RigId rig();

    boolean isValid();

    /** 루트 위치 (탑승 중이면 탈것을 따라간 위치). */
    Location location();

    /** 루트 엔티티. 탈것·승객 처리가 더 필요한 모듈이 쓴다 (예: World 선박 장식의 승객 순서 맞추기). */
    Entity root();

    /** 루트·부품·판정 상자 엔티티 전체. */
    List<Entity> entities();

    // ------------------------------------------------------------------ 이동·방향

    /** 옮긴다. 보간 틱은 설정의 moveTicks. 탈것에 타고 있으면 무시한다. */
    void moveTo(Location to);

    /** 옮긴다. ticks는 클라이언트 보간 시간 (teleport_duration, 0이면 순간 이동). */
    void moveTo(Location to, int ticks);

    /**
     * 진행 방향 (마인크래프트 yaw·pitch). 부품마다 엔티티 회전을 같은 값으로 줘서 몸 전체가 함께 돌고 기울어진다. 머리가 −Z인 모델 보정(yaw + 180°,
     * pitch 부호 반대)은 Core가 넣는다.
     */
    void setHeading(float yaw, float pitch);

    float yaw();

    float pitch();

    /** 루트 엔티티에 태운다 (Wildlife 뇌, World 보트 장식). 이후 위치는 탈것을 따라간다. */
    void mount(Entity vehicle);

    void dismount();

    @Nullable Entity vehicle();

    // ------------------------------------------------------------------ 애니메이션

    /**
     * 동작을 바꾼다 (지금 자세에서 balance.yml rig.blend_ticks 동안 이어 붙인다). 이미 그 동작이면 그대로 둔다.
     *
     * @return 동작이 리그에 없으면 false
     */
    boolean play(String animation);

    /** 동작을 바꾼다. blendTicks 동안 지금 자세에서 새 동작 첫 자세로 이어 붙인 뒤 재생한다. */
    boolean play(String animation, int blendTicks);

    /**
     * 한 번 재생한 뒤 returnTo로 돌아간다 (Wildlife 고유 동작, 보스 패턴). returnTo가 null이면 리그의 default_animation, 그것도
     * 없으면 마지막 자세 유지.
     */
    boolean playOnce(String animation, @Nullable String returnTo);

    /** 애니메이션을 멈추고 기본 자세로 돌아간다. */
    void stop();

    /** 지금 동작 이름. 기본 자세면 null. */
    @Nullable String animation();

    /** 지금 동작의 재생 위치를 프레임으로 맞춘다 (프레임 = balance.yml rig.fps 기준). 개체별 박자·LOD 재동기화용. */
    void setPhase(int frame);

    /** 지금 동작의 재생 위치 (프레임). */
    int frame();

    /** 지금 동작의 프레임 수. */
    int frameCount();

    /** 재생 속도 배율 (1.0 = 원래 속도, 0 = 그 자세로 정지). */
    void setSpeed(double speed);

    double speed();

    // ------------------------------------------------------------------ 표시

    /** 모두에게 보이는지. */
    boolean isPublic();

    /** 볼 수 있는 플레이어 (모두에게 보이면 빈 집합). */
    Set<UUID> viewers();

    /** 볼 수 있는 플레이어를 바꾼다 (모두에게 보이던 리그도 개인 리그가 된다). */
    void setViewers(Collection<? extends Player> players);

    void addViewer(Player player);

    void removeViewer(Player player);

    /** 모두에게 보이게 한다. */
    void showToAll();

    /** {@link #setPartGlow}에서 모든 부품을 가리키는 이름 (어종 그림자 감별 등 뼈 이름을 모를 때). */
    String ALL_PARTS = "*";

    /** 부위 발광 (보스 약점). 색은 rigs/*.yml의 glow_color. bone이 {@link #ALL_PARTS}면 모든 부품. */
    void setPartGlow(String bone, boolean glow);

    /**
     * 동작 이벤트 (rigs/*.yml 동작의 {@code events}, 예: 먹물 {@code ink}·부풀기 {@code hiss}). listener는 (동작 이름,
     * 이벤트 이름)을 메인 스레드에서 받는다. 아무도 안 보거나 LOD 거리 밖이라 갱신을 건너뛴 동안의 이벤트는 내지 않는다.
     */
    default void onEvent(BiConsumer<String, String> listener) {}

    // ------------------------------------------------------------------ 수명

    /** 제거될 때 한 번 불린다 (청크 언로드·종료 포함). */
    void onRemove(Consumer<RemoveReason> listener);

    void remove();
}
