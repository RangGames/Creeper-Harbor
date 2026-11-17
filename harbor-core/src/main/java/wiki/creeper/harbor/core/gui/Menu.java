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

/**
 * 상자 GUI. 칸마다 아이템과 클릭 동작을 둔다. 플레이어가 아이템을 꺼내거나 넣는 것은 {@link MenuListener}가 모두 막는다. 메인 스레드 전용.
 *
 * <p>도감의 미등록 어종처럼 "실루엣 슬롯"은 실루엣 모델을 쓴 아이템을 버튼으로 넣으면 된다.
 */
public class Menu implements InventoryHolder {

    /** 버튼 클릭. */
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

    /** 칸에 버튼을 둔다. handler가 null이면 표시만 한다. */
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
