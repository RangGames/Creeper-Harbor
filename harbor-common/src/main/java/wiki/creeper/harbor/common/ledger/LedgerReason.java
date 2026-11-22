package wiki.creeper.harbor.common.ledger;

/**
 * Currency change reason. New reasons can only be used once added here, so no currency of unknown
 * origin can appear. Stored in the DB by name, so names never change. The list collects the reason
 * codes of each module in 'Per-module plugin design' (docs 20).
 */
public enum LedgerReason {
    // Operations (Core, Season)
    ADMIN_GRANT,
    ADMIN_TAKE,
    SEASON_RESET,

    // Harbor-Skript: gold/mulbineul changes from scripts (proposed)
    SCRIPT_GRANT,
    SCRIPT_TAKE,

    // Jobs: level-up mulbineul, job advancement, line change, skill invest/refund/reset,
    // first-quality reward per tier
    LEVEL_UP,
    JOB_ADVANCE,
    LINE_CHANGE,
    SKILL_INVEST,
    SKILL_REFUND,
    SKILL_RESET,
    TIER_FIRST_QUALITY,

    // Collection
    CODEX_FIRST_CATCH,
    CODEX_REWARD,

    // Quest
    QUEST_REWARD,

    // Economy
    FISH_SELL,
    ITEM_SELL,
    SHOP_BUY,
    CRAFT_COST,
    REPAIR,
    AUCTION_LIST_FEE,
    AUCTION_BUY,
    AUCTION_SALE,
    AUCTION_FEE,
    BIG_DEAL_SELL,
    MAIL_GOLD,

    // Fishing: trap slot expansion (2,000 per slot × trap tier, '7. Balance design' 6.3)
    TRAP_SLOT_EXPAND,

    // World
    TREASURE,
    HOUSING_DECOR,
    // (proposed) Shipyard ship purchase/upgrade, sea chart stage rewards, weekly fishing tournament
    // rank rewards. Ship repair uses the same REPAIR as Economy
    SHIP_PURCHASE,
    SHIP_UPGRADE,
    CHART_REWARD,
    TOURNAMENT_REWARD,

    // Boss
    BOSS_REWARD,

    // Shop (pearls)
    TOPUP,
    SUBSCRIPTION_MONTHLY,
    DONOR_MONTHLY,
    PASS_REWARD,
    PASS_PREMIUM,
    PASS_LEVEL_BUY,
    CHEST_OPEN,
    NAME_CHANGE,
    GIFT,
    REFUND_REVERSAL,
    CHARGEBACK_REVERSAL,
    CHARGEBACK_RESTORE,
}
