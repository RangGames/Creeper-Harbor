package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.model.Keyed;

/** Sea event type (world/events.yml type). */
public enum SeaEventType implements Keyed {
    /** Storm: personal weather, ship durability loss, lightning. */
    STORM,
    /** Ghost ship raid: skeleton pirate waves, ghost ship treasure after clearing them. */
    GHOST_SHIP,
    /** Great fish migration: target species ×3, mythic 'Guide of the Migration'. */
    MIGRATION,
    /** Weekly fishing tournament (rankings in the Season RankingService). */
    TOURNAMENT,
    /** Other events (e.g. return of the first fish; fish_effects only). */
    SPECIAL;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
