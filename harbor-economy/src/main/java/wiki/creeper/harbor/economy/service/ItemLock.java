package wiki.creeper.harbor.economy.service;

import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * 아이템 잠금 (추가 제안). 아이템 PDC {@code harbor:item_locked}에 1을 둔다. 잠근 아이템은 판매 일괄 처리에서 빠지고, 경매 등록이 막힌다.
 * 겹치기는 같은 PDC끼리만 되므로 잠근 묶음과 안 잠근 묶음은 따로 쌓인다. /잠금(손에 든 것)이나 판매 화면 Shift+우클릭으로 켜고 끈다.
 */
public final class ItemLock {

    static final NamespacedKey KEY =
            Objects.requireNonNull(NamespacedKey.fromString("harbor:item_locked"));

    private ItemLock() {}

    public static boolean locked(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Byte v = stack.getPersistentDataContainer().get(KEY, PersistentDataType.BYTE);
        return v != null && v == 1;
    }

    /** 잠금을 뒤집는다. @return 이제 잠겼으면 true */
    public static boolean toggle(ItemStack stack) {
        boolean next = !locked(stack);
        stack.editPersistentDataContainer(
                pdc -> {
                    if (next) {
                        pdc.set(KEY, PersistentDataType.BYTE, (byte) 1);
                    } else {
                        pdc.remove(KEY);
                    }
                });
        return next;
    }
}
