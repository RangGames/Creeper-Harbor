package wiki.creeper.harbor.core.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;

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

    /**
     * 호출한 쪽 트랜잭션 안에서 실행할 아이템 우편 넣기 (추가 제안). 다른 DB 변경(행 삭제 등)과 한 번에 커밋해서, 그 사이 서버가 죽어도 아이템이 사라지거나 두
     * 번 가지 않게 한다. 같은 (받는 사람, source, refId)가 이미 있으면 넣지 않는다. 아이템은 부를 때 직렬화한다 (메인 스레드에서 부른다). 커밋한 뒤
     * {@link #notifyArrived}를 부른다.
     */
    SqlConsumer sendItemInTx(UUID player, ItemStack item, String source, String refId);

    /** 우편이 도착했다고 받는 사람에게 알린다 (다른 서버 포함). {@link #sendItemInTx}를 커밋한 뒤 부른다. */
    void notifyArrived(UUID player);
}
