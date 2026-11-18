package wiki.creeper.harbor.core.api;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.ResourcePackIndex;

/**
 * 커스텀 아이템 모델(item_model) 적용. 모든 모듈은 item_model을 직접 넣지 않고 이것을 거친다.
 *
 * <p>리소스팩에 없는 모델을 넣으면 클라이언트에 보라·검정 "모델 없음" 상자로 보인다. 그래서 두 조건을 모두 만족할 때만 넣고, 아니면 기본 아이템(base_item)
 * 모양 그대로 둔다 (추가 제안).
 *
 * <ul>
 *   <li>balance.yml {@value #SETTING}이 true (리소스팩을 배포 중). 키가 없으면 false
 *   <li>resource_pack.yml({@link ResourcePackIndex})에 그 모델이 있다. 목록이 없으면 이 검사는 건너뛴다
 * </ul>
 */
public final class ItemModels {

    /** 리소스팩을 배포 중이라 커스텀 모델을 쓸지. */
    public static final String SETTING = "items.custom_models";

    private ItemModels() {}

    public static boolean enabled(Balance balance) {
        return balance.bool(SETTING, false);
    }

    /** 이 모델을 지금 아이템에 넣어도 되는지 (켜져 있고, 키 형식이 맞고, 리소스팩 목록에 있다). */
    public static boolean available(ContentSnapshot snapshot, @Nullable String model) {
        if (model == null || !Key.parseable(model) || !enabled(snapshot.single(Balance.TYPE))) {
            return false;
        }
        return !snapshot.typeNames().contains(ResourcePackIndex.TYPE.name())
                || snapshot.single(ResourcePackIndex.TYPE).hasModel(model);
    }

    /**
     * 쓸 수 있는 모델이면 item_model을 넣는다 (Core가 켜진 뒤 어디서나).
     *
     * @return 넣었으면 true (못 쓰면 false, 아이템은 그대로)
     */
    public static boolean apply(ItemStack stack, @Nullable String model) {
        return apply(stack, model, Harbor.api().content().snapshot());
    }

    public static boolean apply(ItemStack stack, @Nullable String model, ContentSnapshot snapshot) {
        if (model == null || !available(snapshot, model)) {
            return false;
        }
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(model));
        return true;
    }
}
