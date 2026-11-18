package wiki.creeper.harbor.economy.api;

import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;

/** 제작 (docs 20 7.2). 스테이션 클릭·대화 액션 craft:open·관리 명령이 이 화면을 연다. 메인 스레드. */
public interface CraftService {

    /** 스테이션(stations.yml) 화면을 연다. 없는 스테이션이거나 제작 장소가 아니면 false. */
    boolean openStation(Player player, HarborId station);

    /** 장소 종류(workshop · cooking · shipyard)의 레시피 화면을 연다. 모르는 종류면 false. */
    boolean openKind(Player player, String kind);
}
