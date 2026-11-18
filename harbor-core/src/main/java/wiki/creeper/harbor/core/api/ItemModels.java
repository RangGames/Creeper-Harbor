package wiki.creeper.harbor.core.api;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;

/**
 * 커스텀 아이템 모델(item_model) 적용. 모든 모듈은 item_model을 직접 넣지 않고 이것을 거친다.
 *
 * <p>리소스팩에 없는 모델을 넣으면 클라이언트에 보라·검정 "모델 없음" 상자로 보인다. 그래서 balance.yml {@value #SETTING}이 true일 때만 넣고,
 * 꺼져 있으면 기본 아이템(base_item) 모양 그대로 둔다. 키가 없으면 꺼진 것으로 본다 (리소스팩 준비 전 기본값, 추가 제안).
 */
public final class ItemModels {

    /** 리소스팩이 준비되어 커스텀 모델을 쓸지. */
    public static final String SETTING = "items.custom_models";

    private ItemModels() {}

    public static boolean enabled(Balance balance) {
        return balance.bool(SETTING, false);
    }

    /** Core가 켜진 뒤 어디서나. */
    public static boolean enabled() {
        return enabled(Harbor.api().content().snapshot().single(Balance.TYPE));
    }

    /**
     * 켜져 있고 키 형식이 맞으면 item_model을 넣는다.
     *
     * @return 넣었으면 true (꺼져 있거나 형식이 틀리면 false, 아이템은 그대로)
     */
    public static boolean apply(ItemStack stack, @Nullable String model) {
        return apply(stack, model, Harbor.api().content().snapshot().single(Balance.TYPE));
    }

    public static boolean apply(ItemStack stack, @Nullable String model, Balance balance) {
        if (model == null || !enabled(balance) || !Key.parseable(model)) {
            return false;
        }
        stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(model));
        return true;
    }
}
