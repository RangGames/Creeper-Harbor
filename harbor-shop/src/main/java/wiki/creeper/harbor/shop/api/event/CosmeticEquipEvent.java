package wiki.creeper.harbor.shop.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.shop.content.CosmeticDef;

/** 장착 칸이 바뀐 뒤 (메인 스레드). 펫·선박 외형 재생성, 다른 모듈의 표시(칭호 테두리·어항 테마) 갱신용. current가 null이면 해제. */
public final class CosmeticEquipEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CosmeticDef.Category slot;
    private final @Nullable HarborId previous;
    private final @Nullable HarborId current;

    public CosmeticEquipEvent(
            Player player,
            CosmeticDef.Category slot,
            @Nullable HarborId previous,
            @Nullable HarborId current) {
        super(player);
        this.slot = slot;
        this.previous = previous;
        this.current = current;
    }

    public CosmeticDef.Category slot() {
        return slot;
    }

    public @Nullable HarborId previous() {
        return previous;
    }

    public @Nullable HarborId current() {
        return current;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
