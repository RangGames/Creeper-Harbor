package wiki.creeper.harbor.npc.api;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * Gift reaction (docs 20 5.4). Thresholds are balance.yml npc.affinity.reactions (proposed values
 * love ≥ 15, like ≥ 5, normal > 0).
 */
public enum GiftReaction implements Keyed {
    LOVE,
    LIKE,
    NORMAL,
    DISLIKE;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
