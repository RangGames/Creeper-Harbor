package wiki.creeper.harbor.npc.api;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * 선물 반응 (docs 20 5.4). 경계는 balance.yml npc.affinity.reactions (제안값 love ≥ 15, like ≥ 5, normal >
 * 0).
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
