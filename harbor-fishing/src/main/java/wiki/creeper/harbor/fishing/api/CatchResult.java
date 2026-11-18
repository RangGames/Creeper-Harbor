package wiki.creeper.harbor.fishing.api;

import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborItem;

/**
 * {@link FishingService#createCatch} 결과.
 *
 * @param stack 만든 아이템 (release_only 어종은 null, 지급은 호출한 쪽이 한다)
 * @param fish 아이템 정보 (크기·품질·고유 ID)
 * @param capped 직업 품질 상한에 걸렸는지
 */
public record CatchResult(@Nullable ItemStack stack, HarborItem.Fish fish, boolean capped) {}
