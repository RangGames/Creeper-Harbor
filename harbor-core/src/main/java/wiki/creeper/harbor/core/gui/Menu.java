package wiki.creeper.harbor.core.gui;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborSounds;

/**
 * Chest GUI. Each slot holds an item and a click action. {@link MenuListener} blocks players from
 * taking out or putting in items. Main thread only.
 *
 * <p>For a "silhouette slot", such as an unregistered species in the collection book, add an item
 * using the silhouette model as the button.
 */
public class Menu implements InventoryHolder {

    /** Sound played when a button is pressed. */
    public static final String CLICK_SOUND = "harbor:ui.click";

    /** Button click. */
    @FunctionalInterface
    public interface ClickHandler extends Consumer<InventoryClickEvent> {}

    private final Inventory inventory;
    private final Map<Integer, ClickHandler> handlers = new HashMap<>();
    private @Nullable Consumer<Player> onClose;

    public Menu(Component title, int rows) {
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("상자 GUI는 1~6줄입니다: " + rows);
        }
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public int size() {
        return inventory.getSize();
    }

    /** Puts a button in a slot. If handler is null, it is display-only. */
    public Menu button(int slot, ItemStack item, @Nullable ClickHandler handler) {
        inventory.setItem(slot, item);
        if (handler == null) {
            handlers.remove(slot);
        } else {
            handlers.put(slot, handler);
        }
        return this;
    }

    public Menu clear(int slot) {
        inventory.setItem(slot, null);
        handlers.remove(slot);
        return this;
    }

    public Menu onClose(Consumer<Player> action) {
        this.onClose = action;
        return this;
    }

    public void open(Player player) {
        player.openInventory(inventory);
    }

    void handleClick(InventoryClickEvent event) {
        ClickHandler handler = handlers.get(event.getRawSlot());
        if (handler != null) {
            if (event.getWhoClicked() instanceof Player player) {
                // Button click sound ('15. Sound' 4.5 ui.click). Display-only slots make no sound
                HarborSounds.play(player, CLICK_SOUND);
            }
            handler.accept(event);
        }
    }

    void handleClose(Player player) {
        Consumer<Player> action = onClose;
        if (action != null) {
            action.accept(player);
        }
    }
}
