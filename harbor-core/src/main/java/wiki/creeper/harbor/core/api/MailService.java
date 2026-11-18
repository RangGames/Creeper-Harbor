package wiki.creeper.harbor.core.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * 우편함 (docs 20 0.2, 7.4). 인터페이스는 Core에 두고 Economy가 구현을 등록한다. Fishing(통발 자동 회수)·Quest(보상 반환)·
 * Collection(기부 실패 반환)은 Core에만 의존해 Economy와 순환 의존이 생기지 않는다. 구현이 없으면 ({@link HarborApi#mail()}이 비어
 * 있으면) 호출한 쪽이 보류한다. 어느 스레드에서 불러도 되고 결과는 DB 커밋 후 완료된다.
 */
public interface MailService {

    CompletableFuture<Void> sendItem(
            UUID player, ItemStack item, String source, @Nullable String refId);

    /** 골드 우편: 수령할 때 원장에 MAIL_GOLD로 지급한다. */
    CompletableFuture<Void> sendGold(UUID player, long gold, String source, @Nullable String refId);

    /** 안 받은 우편 수. */
    CompletableFuture<Integer> unclaimed(UUID player);
}
