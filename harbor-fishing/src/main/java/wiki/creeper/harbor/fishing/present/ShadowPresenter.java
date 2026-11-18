package wiki.creeper.harbor.fishing.present;

import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * 물고기 그림자·점프 연출 (docs 20 3.4 '그림자 · 점프 연출'). 문서는 Core RigInstance(어종 리그, 캐스터에게만 표시)를 쓰지만 아직 없으므로 이
 * 인터페이스 뒤에 두고 기본 구현({@link DisplayShadowPresenter})은 ItemDisplay 하나로 그린다. RigInstance가 생기면 이 인터페이스의
 * 구현만 바꾼다 (리그 연결 필요 지점). 메인 스레드 전용.
 */
public interface ShadowPresenter {

    /**
     * 그림자를 만든다 (캐스터에게만 보임).
     *
     * @param at 처음 위치 (물속)
     * @param preset 등급 연출 (기본 그림자 모델·크기·글로우 색)
     * @param gradeGlow 그림자 감별(feature_shadow_grade)이면 등급 색 테두리
     */
    Shadow spawn(
            Player viewer,
            Location at,
            FishDef fish,
            FishingPresets.GradePreset preset,
            boolean gradeGlow);

    /** 그림자 하나. */
    interface Shadow {

        Location location();

        /** 입질: 그림자가 찌 아래로 잠긴다 (리그 bite 동작). */
        default void bite() {}

        /** 보간 이동 (teleport_duration, 최대 59틱). */
        void moveTo(Location target, int ticks);

        /** 점프 패턴: 수면 위로 뛰어올랐다가 돌아온다. extraViewers에게는 이 장면만 잠깐 보인다. */
        void jump(Collection<Player> extraViewers);

        boolean isValid();

        void remove();
    }
}
