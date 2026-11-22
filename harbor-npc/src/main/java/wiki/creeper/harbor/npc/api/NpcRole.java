package wiki.creeper.harbor.npc.api;

import wiki.creeper.harbor.common.model.Keyed;

/** NPC role ('11. Data design' 2.5). wanderer requires a schedule. */
public enum NpcRole implements Keyed {
    STORY,
    JOB,
    COLLECT,
    SHOP,
    REGION,
    WANDERER;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
