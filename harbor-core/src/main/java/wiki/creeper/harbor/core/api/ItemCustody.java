package wiki.creeper.harbor.core.api;

import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 모듈이 플레이어 아이템을 인벤토리 밖에 잠시 맡아 두는 곳 (판매 상자, 기부함, 확인 대기 목록 등, 추가 제안). 자동 저장·거래 뒤 저장은 열린 창을 닫지 않으므로,
 * Core가 저장본을 뜰 때 여기서 받은 아이템을 저장본 인벤토리(넘치면 엔더 상자)에 넣어 둔다. 그 사이 서버가 죽어도 맡긴 아이템이 사라지지 않고 인벤토리로 돌아온다.
 * 퇴장·이동·종료 저장 때는 부르지 않는다 (창을 닫거나 퇴장 처리에서 모듈이 직접 돌려준다).
 *
 * <p>맡은 아이템이 DB 등 다른 곳에도 기록돼 있으면 등록하지 않는다 (복제). 처리 중(트랜잭션 진행 중)인 아이템도 넣지 않는다.
 */
@FunctionalInterface
public interface ItemCustody {

    /** 메인 스레드. 지금 이 플레이어 것으로 맡고 있는 아이템. 저장본에만 쓰므로 복사본을 돌려준다. */
    List<ItemStack> held(Player player);
}
