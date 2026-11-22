package wiki.creeper.harbor.economy.service;

import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Item lock (proposed). Sets 1 in the item PDC {@code harbor:item_locked}. Locked items are skipped
 * by bulk selling and cannot be auctioned. Stacking only works between identical PDCs, so locked
 * and unlocked stacks pile up separately. Toggled with /잠금 (held item) or Shift+right-click in the
 * sell screen.
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

    /** Toggles the lock. @return true if now locked */
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
