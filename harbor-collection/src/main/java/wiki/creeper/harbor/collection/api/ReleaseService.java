package wiki.creeper.harbor.collection.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 방류 (docs 20 4.4, Phase 3). 서버 플래그 climax_done이 서면 열린다. 판매 대신이라 골드는 주지 않고, 희귀 이상이면 평판(NPC
 * ReputationService, 출처 RELEASE, 하루 상한)과 행운 버프(size_luck)를 준다. 고유 ID 아이템은 장부를 RELEASED로 바꾼다.
 */
public interface ReleaseService {

    /** 결과. */
    enum Result {
        RELEASED,
        /** 방류가 아직 열리지 않음 (climax_done 전). */
        LOCKED,
        /** 어종 아이템이 아니거나 release_only 등 방류 대상이 아님. */
        NOT_ALLOWED,
        /** 확인 화면에서 취소. */
        CANCELLED,
        /** 장부 상태가 맞지 않거나 저장 실패 (아이템은 돌려준다). */
        FAILED
    }

    boolean available();

    /**
     * 플레이어 인벤토리에 있는 어종 아이템 한 마리를 방류한다 (확인 화면 후). 결과는 메인 스레드에서 완료된다.
     *
     * @param stack 인벤토리 안의 아이템 (한 마리를 뺀다)
     */
    CompletableFuture<Result> release(Player player, ItemStack stack);
}
