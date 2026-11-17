package wiki.creeper.harbor.economy.api;

import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;

/** NPC 상점 (NPC 클릭, World의 선박·장식 구매도 이것을 쓴다). */
public interface ShopService {

    /** 상점 화면을 연다. 없는 상점이면 false. */
    boolean open(Player player, HarborId shop);
}
