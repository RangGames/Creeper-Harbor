package wiki.creeper.harbor.fishing.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 통발 (docs 20 3.4 TrapService, '12. 월드 · 하우징 · 통발' 5장). 설치·열기·수확은 플레이어가 통발 블록으로 하고, 다른 모듈은 이 조회·확장
 * API만 쓴다. 메인 스레드에서 부르고 future는 메인 스레드에서 완료된다.
 */
public interface TrapService {

    /** 플레이어의 통발 목록 (이번 시즌). */
    CompletableFuture<List<TrapInfo>> traps(UUID owner);

    /**
     * 통발 슬롯 1칸 확장 (가격 = 슬롯당 balance.yml fishing.trap.slot_price × 통발 단계). 골드 차감(원장
     * TRAP_SLOT_EXPAND)과 traps 갱신을 한 트랜잭션으로 처리한다. Economy 상점 화면에서 이 메서드를 불러 결제를 연결한다.
     */
    CompletableFuture<SlotPurchase> purchaseSlot(Player owner, long trapId);

    /** 통발 수확물이면 true (custom_data에 trap 출처, Economy 경매 감시용). */
    boolean isTrapCatch(ItemStack stack);

    /** 슬롯 확장 결과. */
    enum SlotPurchase {
        OK,
        NOT_FOUND,
        NOT_OWNER,
        MAX_REACHED,
        NO_GOLD,
        FAILED
    }
}
