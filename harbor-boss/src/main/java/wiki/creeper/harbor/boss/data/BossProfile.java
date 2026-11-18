package wiki.creeper.harbor.boss.data;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRole;

/**
 * PlayerComponent&lt;BossProfile&gt; (docs 20 10.3): 선호 역할, 인스턴스로 가기 전 위치(돌아올 곳). 현재 조우 ID는 메모리
 * 전용이라 EncounterManager가 따로 들고, 지급 완료 표시는 플레이어 PDC harbor:boss_delivered에 둔다.
 */
public record BossProfile(@Nullable BossRole preferred, @Nullable ReturnPoint returnPoint) {

    public static final BossProfile EMPTY = new BossProfile(null, null);

    /** 인스턴스로 떠나기 전 위치. 본 서버에 다시 접속하면 여기로 돌려보내고 지운다. */
    public record ReturnPoint(
            String server, String world, double x, double y, double z, float yaw, float pitch) {}

    public BossProfile withPreferred(@Nullable BossRole role) {
        return new BossProfile(role, returnPoint);
    }

    public BossProfile withReturn(@Nullable ReturnPoint point) {
        return new BossProfile(preferred, point);
    }
}
