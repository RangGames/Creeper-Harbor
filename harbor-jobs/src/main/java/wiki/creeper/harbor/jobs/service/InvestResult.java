package wiki.creeper.harbor.jobs.service;

/** Skill investment result. Check order follows docs 20 2.4 SkillAllocator. */
public enum InvestResult {
    OK,
    UNKNOWN_NODE,
    JOB_LOCKED,
    TIER_LOCKED,
    REQUIRES_NODES,
    MAX_RANK,
    NOT_ENOUGH_MULBINEUL,
    BUSY,
    FAILED;

    /** messages_ko.yml key. */
    public String messageKey() {
        return "jobs.invest." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
