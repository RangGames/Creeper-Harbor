package wiki.creeper.harbor.npc.api;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * Reputation source (docs 20 5.4 reputation table). Per-source defaults and daily caps are
 * balance.yml {@code reputation.sources.<key>} and {@code reputation.daily_caps.<key>}. The
 * reputation_gain modifier (economy line "harbor investment") is applied only to sources that are
 * {@link #contribution()}.
 */
public enum ReputationSource implements Keyed {
    MAIN_STORY(false),
    REGION_QUEST(false),
    DAILY_REQUEST(false),
    WEEKLY_REQUEST(false),
    /** Collection request (CONTRIBUTION). */
    COLLECT_REQUEST(true),
    BOSS_FIRST_KILL(false),
    WORLD_BOSS(false),
    /** Release, rare or above (CONTRIBUTION). Daily cap 30. */
    RELEASE(true),
    /** Admin command (admin_log instead of a ledger). No cap or modifier. */
    ADMIN(false),
    OTHER(false);

    private final boolean contribution;

    ReputationSource(boolean contribution) {
        this.contribution = contribution;
    }

    /** Whether this is a harbor contribution (CONTRIBUTION) source. */
    public boolean contribution() {
        return contribution;
    }

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
