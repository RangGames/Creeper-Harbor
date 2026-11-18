package wiki.creeper.harbor.core.api;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * 리그를 띄울 때의 설정. {@code RigOptions.privateTo(player).withAnimation("swim").withPhase(-1)}처럼 이어서 만든다.
 *
 * @param viewers 볼 수 있는 플레이어. null이면 모두에게 보인다 (보스·수족관). 개인 연출(그림자·컷씬)은 대상만 넣는다
 * @param scale 리그 크기 배율 (리그 정의의 scale에 곱한다. 큰 개체일수록 크게)
 * @param animation 처음 재생할 동작. null이면 리그의 default_animation
 * @param phase 처음 동작의 시작 프레임. −1이면 무작위 (같은 무리라도 박자가 달라지게, '17-3' 9.4)
 * @param onHit 부위 판정 상자 클릭 콜백. null이면 판정 상자(Interaction)를 만들지 않는다
 * @param moveTicks 이동·방향 보간 틱 (teleport_duration). −1이면 balance.yml rig.move_ticks
 * @param blockLight 고정 밝기 (블록, 0~15). −1이면 부품 설정 또는 주변 밝기
 * @param skyLight 고정 밝기 (하늘, 0~15)
 */
public record RigOptions(
        @Nullable Set<UUID> viewers,
        double scale,
        @Nullable String animation,
        int phase,
        @Nullable Consumer<RigHit> onHit,
        int moveTicks,
        int blockLight,
        int skyLight) {

    public RigOptions {
        if (viewers != null) {
            viewers = Set.copyOf(viewers);
        }
        if (scale <= 0) {
            throw new IllegalArgumentException("scale은 0보다 커야 합니다: " + scale);
        }
    }

    /** 모두에게 보이는 리그. */
    public static RigOptions publicRig() {
        return new RigOptions(null, 1.0, null, 0, null, -1, -1, -1);
    }

    /** 이 플레이어들에게만 보이는 리그. */
    public static RigOptions privateTo(Collection<? extends Player> players) {
        Set<UUID> ids = new HashSet<>();
        players.forEach(p -> ids.add(p.getUniqueId()));
        return new RigOptions(ids, 1.0, null, 0, null, -1, -1, -1);
    }

    public static RigOptions privateTo(Player player) {
        return new RigOptions(Set.of(player.getUniqueId()), 1.0, null, 0, null, -1, -1, -1);
    }

    public boolean isPublic() {
        return viewers == null;
    }

    public RigOptions withScale(double newScale) {
        return new RigOptions(
                viewers, newScale, animation, phase, onHit, moveTicks, blockLight, skyLight);
    }

    public RigOptions withAnimation(@Nullable String newAnimation) {
        return new RigOptions(
                viewers, scale, newAnimation, phase, onHit, moveTicks, blockLight, skyLight);
    }

    public RigOptions withPhase(int newPhase) {
        return new RigOptions(
                viewers, scale, animation, newPhase, onHit, moveTicks, blockLight, skyLight);
    }

    /** 부위별 판정 상자(Interaction)를 만들고 클릭을 넘긴다 (Boss). */
    public RigOptions withHitboxes(Consumer<RigHit> handler) {
        return new RigOptions(
                viewers, scale, animation, phase, handler, moveTicks, blockLight, skyLight);
    }

    public RigOptions withMoveTicks(int ticks) {
        return new RigOptions(viewers, scale, animation, phase, onHit, ticks, blockLight, skyLight);
    }

    /** 밝기 고정 (용암 속 어종이 어둡지 않게 등). */
    public RigOptions withBrightness(int block, int sky) {
        if (block < 0 || block > 15 || sky < 0 || sky > 15) {
            throw new IllegalArgumentException("밝기는 0~15여야 합니다");
        }
        return new RigOptions(viewers, scale, animation, phase, onHit, moveTicks, block, sky);
    }
}
