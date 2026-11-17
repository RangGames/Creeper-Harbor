package wiki.creeper.harbor.economy.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * 우편함 (추가 제안, docs 20 7.4). 경매 낙찰품·반환, 통발 자동 회수(Fishing), 보상 재발송, 상점 구매 넘친 물건. 어느 스레드에서 불러도 되고 결과는
 * DB 커밋 후 완료된다.
 */
public interface MailService {

    CompletableFuture<Void> sendItem(
            UUID player, ItemStack item, String source, @Nullable String refId);

    /** 골드 우편: 수령할 때 원장에 MAIL_GOLD로 지급한다. */
    CompletableFuture<Void> sendGold(UUID player, long gold, String source, @Nullable String refId);

    /** 안 받은 우편 수. */
    CompletableFuture<Integer> unclaimed(UUID player);
}
