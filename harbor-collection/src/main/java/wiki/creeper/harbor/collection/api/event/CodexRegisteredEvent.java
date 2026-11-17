package wiki.creeper.harbor.collection.api.event;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import wiki.creeper.harbor.collection.api.CodexScope;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;

/** 시즌·누적 도감 첫 등록 (Quest 조건 재평가, Shop 패스 경험치). */
public final class CodexRegisteredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final FishId fish;
    private final CodexScope scope;
    private final Grade grade;

    public CodexRegisteredEvent(UUID player, FishId fish, CodexScope scope, Grade grade) {
        this.player = player;
        this.fish = fish;
        this.scope = scope;
        this.grade = grade;
    }

    public UUID player() {
        return player;
    }

    public FishId fish() {
        return fish;
    }

    public CodexScope scope() {
        return scope;
    }

    public Grade grade() {
        return grade;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
