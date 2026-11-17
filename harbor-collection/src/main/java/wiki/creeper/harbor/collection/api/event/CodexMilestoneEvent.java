package wiki.creeper.harbor.collection.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * 도감 마일스톤 (지역 완성, 누적 달성률 단계). 칭호는 Season(TitleService), 치장품은 Shop이 이 이벤트를 듣고 지급한다. Season 모듈이 생기기
 * 전에는 칭호 키만 실어 보낸다.
 *
 * @see Kind
 */
public final class CodexMilestoneEvent extends Event {

    /** 마일스톤 종류. */
    public enum Kind {
        REGION_COMPLETE,
        TOTAL_PERCENT
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final Kind kind;
    private final String key;
    private final int percent;
    private final @Nullable String title;

    public CodexMilestoneEvent(
            UUID player, Kind kind, String key, int percent, @Nullable String title) {
        this.player = player;
        this.kind = kind;
        this.key = key;
        this.percent = percent;
        this.title = title;
    }

    public UUID player() {
        return player;
    }

    public Kind kind() {
        return kind;
    }

    /** 지역 이름 또는 달성률 단계 키. */
    public String key() {
        return key;
    }

    public int percent() {
        return percent;
    }

    /** 지급할 칭호 ID (없으면 null). */
    public @Nullable String title() {
        return title;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
