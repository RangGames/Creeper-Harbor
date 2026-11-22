package wiki.creeper.harbor.npc.api;

import wiki.creeper.harbor.common.model.Keyed;

/** Source of affinity. */
public enum AffinitySource implements Keyed {
    /** First talk of the day. */
    GREETING,
    GIFT,
    /** Quest reward (no cap). */
    QUEST,
    /** Dialog action affinity:add (only once, via the once flag). */
    DIALOG,
    ADMIN,
    OTHER;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
