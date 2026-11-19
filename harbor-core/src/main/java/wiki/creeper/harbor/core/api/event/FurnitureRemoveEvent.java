package wiki.creeper.harbor.core.api.event;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.HarborId;

/**
 * 플레이어가 가구를 회수하기 직전 (메인 스레드, 추가 제안). 취소하면 그대로 둔다. {@link #setReturnItem(boolean)
 * setReturnItem(false)}면 아이템을 돌려주지 않는다 (하우징 장식처럼 듣는 쪽이 따로 보관할 때).
 *
 * <p>{@link #allowed()}: 설치한 사람이거나 harbor.furniture.manage 권한이 있으면 처음부터 true. 아니면 듣는 쪽이 허락해야 한다
 * (하우징: 플롯 건축 권한). 끝까지 false면 회수하지 않는다.
 */
public final class FurnitureRemoveEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location location;
    private final HarborId furniture;
    private final ItemStack item;
    private boolean returnItem = true;
    private boolean allowed;
    private boolean cancelled;

    public FurnitureRemoveEvent(
            Player player, Location location, HarborId furniture, ItemStack item, boolean allowed) {
        this.player = player;
        this.allowed = allowed;
        this.location = location;
        this.furniture = furniture;
        this.item = item;
    }

    public Player player() {
        return player;
    }

    /** 가구가 놓인 블록 바닥 가운데. */
    public Location location() {
        return location.clone();
    }

    public HarborId furniture() {
        return furniture;
    }

    /** 설치할 때 쓴 아이템 (1개, 복사본). */
    public ItemStack item() {
        return item.clone();
    }

    public boolean allowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }

    public boolean returnItem() {
        return returnItem;
    }

    public void setReturnItem(boolean returnItem) {
        this.returnItem = returnItem;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
