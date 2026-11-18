package wiki.creeper.harbor.fishing.api;

import org.bukkit.Location;
import wiki.creeper.harbor.fishing.api.event.FishingCastEvent;

/**
 * 대기 중 그림자 대행 SPI (docs 20 3.2·3.4 Wildlife 연동). Wildlife가 {@link FishingCastEvent#claimShadow}(또는
 * {@link FishingService#claimShadow})로 넘긴다. Wildlife → Fishing 한 방향이라 Fishing은 Wildlife를 직접 부르지 않고
 * 이 인터페이스만 부른다.
 *
 * <p>순서: {@link #approach} (대기 시간 동안 찌로 다가옴) → {@link #bite} (입질 순간, 개체를 잠기게 함) → Fishing 그림자가 같은
 * 자리에서 이어받아 미니게임 진행 → {@link #release} (세션이 끝나면 개체를 풀어 줌). 대기 중 {@link #isValid}가 false가 되면
 * Fishing이 즉시 기본 그림자로 바꾸고 {@link #release}를 부른다. 모든 메서드는 메인 스레드에서 불린다.
 */
public interface ShadowDriver {

    /**
     * 찌로 다가오기 시작한다.
     *
     * @param hook 찌 위치
     * @param ticks 입질까지 남은 틱 (이 시간 안에 찌에 닿으면 된다)
     */
    void approach(Location hook, int ticks);

    /** 입질 순간. 개체를 물속으로 잠기게 한다. Fishing이 이 위치에서 미니게임 그림자를 이어서 그린다. */
    void bite();

    /** 개체의 현재 위치 (그림자 인계 지점). */
    Location location();

    /** 개체가 아직 살아 있고 대행할 수 있는지 (청크 언로드·사망이면 false). */
    boolean isValid();

    /**
     * 세션이 끝났다. 개체를 풀어 준다 (원래 행동으로 돌아감).
     *
     * @param caught 그 개체의 어종이 실제로 낚였는지 (Wildlife가 개체를 지울지 정한다)
     */
    void release(boolean caught);
}
