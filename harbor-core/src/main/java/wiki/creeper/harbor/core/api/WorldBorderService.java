package wiki.creeper.harbor.core.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 개인 세계 경계 중재 (docs 20 0.2). 플레이어당 하나뿐인 개인 세계 경계를 모듈들이 같이 쓰도록, 요청은 우선순위별로 보관하고 가장 높은 것 하나만 보여 준다.
 * 요청을 지우면 다음 순위 요청으로 돌아가고, 남은 요청이 없으면 개인 경계를 없앤다. 메인 스레드 전용. 퇴장하면 모두 지운다.
 */
public interface WorldBorderService {

    /** 높은 순서: 보스 경기장 > Fishing 텐션 경고 > World 환경 경고. */
    enum Priority {
        WORLD_ENVIRONMENT,
        FISHING_TENSION,
        BOSS_ARENA
    }

    /**
     * @param center 경계 중심
     * @param size 한 변 길이 (블록)
     * @param warningDistance 화면이 붉어지기 시작하는 거리. 경고 연출만 할 때는 크게 준다
     */
    record Border(Location center, double size, int warningDistance) {

        public Border {
            center = center.clone();
            if (size <= 0) {
                throw new IllegalArgumentException("size > 0");
            }
        }

        /** 경계는 멀리 두고 화면만 붉게 만드는 경고 연출 (텐션·환경 경고용). */
        public static Border warning(Location at) {
            return new Border(at, 1_000_000, Integer.MAX_VALUE);
        }
    }

    void request(Player player, Priority priority, Border border);

    void clear(Player player, Priority priority);
}
