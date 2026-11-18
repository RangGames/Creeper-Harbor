package wiki.creeper.harbor.shop.api;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.shop.content.CosmeticDef;

/**
 * 코스메틱 소유·장착 (docs 20 12.2). 소유는 계정 기록(cosmetics_owned)이라 시즌 초기화·거래 대상이 아니다. 장착 칸은 분류마다 하나.
 *
 * <p>낚싯대 스킨은 아이템의 item_model만 바꾸고 custom_data(PDC)에 harbor:skin(스킨 ID·소유자)을 적는다. 경매 등록·거래·다른 사람이 주운
 * 경우 Economy 등이 {@link #stripSkin}으로 원래 모델로 되돌린다. 성능 값(stats)은 건드리지 않는다.
 *
 * <p>캐시를 읽는 메서드는 메인 스레드 전용이고, future는 메인 스레드에서 완료된다.
 */
public interface CosmeticService {

    /** 접속 중인 플레이어가 가졌는지 (캐시). */
    boolean owns(UUID player, HarborId cosmetic);

    /** 접속 중인 플레이어의 보유 목록 (캐시). */
    Set<HarborId> owned(UUID player);

    /** 장착한 코스메틱 (접속 중, 캐시). */
    Optional<HarborId> equipped(UUID player, CosmeticDef.Category slot);

    /**
     * 준다 (접속 여부 무관, 즉시 쓰기). 이미 가졌으면 false.
     *
     * @param source cosmetics_owned.source (PASS · CODEX · FESTIVAL · ADMIN …)
     */
    CompletableFuture<Boolean> grant(UUID player, HarborId cosmetic, String source, String refId);

    /** 회수한다 (관리자). 장착 중이면 장착도 푼다. 있었으면 true. */
    CompletableFuture<Boolean> revoke(UUID player, HarborId cosmetic);

    /** 장착한다. 가지고 있지 않으면 false. */
    boolean equip(Player player, HarborId cosmetic);

    /** 그 칸을 비운다. */
    void unequip(Player player, CosmeticDef.Category slot);

    /** 아이템에 입힌 낚싯대 스킨 ID. */
    Optional<HarborId> skinOf(ItemStack stack);

    /** 낚싯대 스킨을 벗겨 원래 모델로 되돌린다. 스킨이 있었으면 true. */
    boolean stripSkin(ItemStack stack);

    /**
     * 펫·선박 장식을 숨길 조건을 등록한다 (예: Boss가 보스 경기장 안 플레이어). 하나라도 true면 숨긴다. owner가 꺼지면 빠진다. 메인 스레드에서 불린다.
     */
    void registerPetSuppressor(Plugin owner, Predicate<Player> hide);
}
