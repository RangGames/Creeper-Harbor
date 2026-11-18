package wiki.creeper.harbor.collection.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 개인 어항 전시 (docs 20 4.4, 0.1). 수조 자리(DisplayAnchor)와 편집 권한은 World, 전시 내용·칸 수·헤엄 연출·방문객 정보는
 * Collection이다. Harbor-World가 없으면 등록되지 않는다. 결과 future는 메인 스레드에서 완료된다.
 *
 * <ul>
 *   <li>전시 칸 수 = 기본 + 시즌 간 최고 평판 등급 보너스 (D11, 줄지 않음)
 *   <li>테마 칸 수 = 기본 + 후원 등급 추가 칸 (D14, 외형만. 후원이 끝나면 추가 칸의 테마만 기본으로 돌아간다)
 * </ul>
 */
public interface DisplayCaseService {

    /** 전시 칸 수 (오프라인이면 DB의 최고 평판 등급). */
    CompletableFuture<Integer> slotCount(UUID owner);

    /** 테마 칸 수 (후원 등급은 Redis에서 읽는다). */
    CompletableFuture<Integer> themeSlotCount(UUID owner);

    /** 플롯의 전시 칸 (slot 순서). */
    CompletableFuture<List<DisplaySlot>> slots(long plotId);

    /**
     * 칸에 어종을 전시한다. 편집 권한(World EDIT_DISPLAY), 칸 수, 누적 도감 등록 여부를 확인하고 즉시 저장한 뒤 다시 그린다.
     *
     * @return 저장했으면 true
     */
    CompletableFuture<Boolean> set(Player editor, long plotId, int slot, FishId fish);

    /** 칸의 테마를 바꾼다 (null이면 기본). 보유 여부와 테마 칸 수를 확인한다. */
    CompletableFuture<Boolean> setTheme(
            Player editor, long plotId, int slot, @Nullable HarborId theme);

    /** 칸을 비운다 (관리자 명령은 editor 없이 부른다). */
    CompletableFuture<Boolean> clear(@Nullable Player editor, long plotId, int slot);
}
