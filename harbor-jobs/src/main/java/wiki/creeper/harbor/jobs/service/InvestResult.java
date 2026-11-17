package wiki.creeper.harbor.jobs.service;

/** 스킬 투자 결과. 검사 순서는 docs 20 2.4 SkillAllocator를 따른다. */
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

    /** messages_ko.yml 키. */
    public String messageKey() {
        return "jobs.invest." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
