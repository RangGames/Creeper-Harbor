package wiki.creeper.harbor.fishing.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Quality;

/**
 * 포획 연출 코스메틱 (docs 20 3.2, 추가 제안). Shop이 {@link FishingService#registerPresentation}으로 등록해 장착한 포획
 * 연출 (빛기둥 색, 폭죽 모양, 효과음)을 바꾼다. Fishing은 등급 프리셋을 재생하기 전에 부르고, true를 돌려주면 프리셋의 빛기둥·폭죽·효과음을 건너뛴다 (채팅
 * 문구·공지·타이틀은 그대로). 메인 스레드에서 불린다.
 */
@FunctionalInterface
public interface CatchPresentationOverride {

    /**
     * @return 기본 연출을 대신했으면 true
     */
    boolean present(Player player, CatchPresentation catchInfo);

    /**
     * 포획 정보.
     *
     * @param at 연출 위치 (플레이어 위치)
     * @param intensity 플레이어 연출 강도 설정 (0 최소, 1 보통, 2 최대)
     */
    record CatchPresentation(
            FishDef fish,
            double sizeCm,
            Quality quality,
            boolean firstCatch,
            Location at,
            int intensity) {

        public CatchPresentation {
            at = at.clone();
        }
    }
}
