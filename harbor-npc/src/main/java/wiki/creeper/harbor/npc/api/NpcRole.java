package wiki.creeper.harbor.npc.api;

import wiki.creeper.harbor.common.model.Keyed;

/** NPC 역할 ('11. 데이터 설계' 2.5). wanderer는 schedule이 필수다. */
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
