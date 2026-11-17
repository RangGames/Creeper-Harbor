package wiki.creeper.harbor.core.api;

import java.time.Instant;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.Quality;

/** 아이템 생성·읽기. 메인 스레드에서 쓴다. */
public interface ItemService {

    ItemStack create(ItemDef def, int amount);

    /**
     * 어종 아이템을 만든다. ★5·전설·신화는 고유 ID를 발급하고 fish_ledger에 기록한다 (비동기).
     *
     * @param sizeCm 크기 (소수 첫째 자리까지 저장)
     */
    ItemStack createFish(
            FishDef def, double sizeCm, Quality quality, PlayerRef catcher, Instant caughtAt);

    /** Harbor 아이템이 아니면 빈 Optional. 옛 형식 버전은 새 형식으로 읽는다. */
    Optional<HarborItem> read(ItemStack stack);

    /** 옛 형식 버전이면 custom_data를 새 형식으로 고친다. 고쳤으면 true. */
    boolean upgrade(ItemStack stack);
}
