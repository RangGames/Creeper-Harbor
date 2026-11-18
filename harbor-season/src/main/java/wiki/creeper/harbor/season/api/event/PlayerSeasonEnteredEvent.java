package wiki.creeper.harbor.season.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.common.model.SeasonId;

/** 플레이어가 새 시즌에 처음 접속해 정리(SeasonGate)가 끝나고 저장된 직후 메인 스레드에서 발생한다. */
public final class PlayerSeasonEnteredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final int previousSeason;
    private final SeasonId season;
    private final int removedStacks;

    public PlayerSeasonEnteredEvent(
            Player player, int previousSeason, SeasonId season, int removedStacks) {
        this.player = player;
        this.previousSeason = previousSeason;
        this.season = season;
        this.removedStacks = removedStacks;
    }

    public Player player() {
        return player;
    }

    /** 마지막으로 정리한 시즌 (처음이면 0). */
    public int previousSeason() {
        return previousSeason;
    }

    public SeasonId season() {
        return season;
    }

    /** 지운 아이템 묶음 수. */
    public int removedStacks() {
        return removedStacks;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
