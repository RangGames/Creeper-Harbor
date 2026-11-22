package wiki.creeper.harbor.core.gui;

import java.util.List;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Paged chest GUI. Fills the top (rows-1) rows with entries and puts previous/next buttons on the
 * last row.
 *
 * @param <T> entry data
 */
public final class PagedMenu<T> extends Menu {

    /** Turns one entry into a button. */
    public record Entry(ItemStack icon, Menu.ClickHandler onClick) {}

    private final List<T> items;
    private final Function<T, Entry> render;
    private final ItemStack previous;
    private final ItemStack next;
    private final int perPage;
    private int page;

    public PagedMenu(
            Component title,
            int rows,
            List<T> items,
            Function<T, Entry> render,
            ItemStack previous,
            ItemStack next) {
        super(title, rows);
        if (rows < 2) {
            throw new IllegalArgumentException("페이지 GUI는 2줄 이상이어야 합니다");
        }
        this.items = List.copyOf(items);
        this.render = render;
        this.previous = previous;
        this.next = next;
        this.perPage = (rows - 1) * 9;
    }

    public int pageCount() {
        return Math.max(1, (items.size() + perPage - 1) / perPage);
    }

    public int page() {
        return page;
    }

    public void show(Player player, int requestedPage) {
        page = Math.clamp(requestedPage, 0, pageCount() - 1);
        for (int slot = 0; slot < size(); slot++) {
            clear(slot);
        }
        int start = page * perPage;
        for (int i = 0; i < perPage && start + i < items.size(); i++) {
            Entry entry = render.apply(items.get(start + i));
            button(i, entry.icon(), entry.onClick());
        }
        int navRow = size() - 9;
        if (page > 0) {
            button(navRow, previous, e -> show(player, page - 1));
        }
        if (page < pageCount() - 1) {
            button(navRow + 8, next, e -> show(player, page + 1));
        }
        if (player.getOpenInventory().getTopInventory().getHolder(false) != this) {
            open(player);
        }
    }
}
