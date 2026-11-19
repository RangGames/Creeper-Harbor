package wiki.creeper.harbor.common.ledger;

/**
 * 재화 변경 사유. 새 사유는 여기에 추가해야만 쓸 수 있어서, 출처를 모르는 재화가 생기지 않는다. DB에는 이름 그대로 저장하므로 이름을 바꾸지 않는다. 목록은 '모듈별
 * 플러그인 설계'(docs 20) 각 모듈의 사유 코드를 모은 것이다.
 */
public enum LedgerReason {
    // 운영 (Core·Season)
    ADMIN_GRANT,
    ADMIN_TAKE,
    SEASON_RESET,

    // Harbor-Skript: 스크립트의 골드·물비늘 증감 (추가 제안)
    SCRIPT_GRANT,
    SCRIPT_TAKE,

    // Jobs: 레벨업 물비늘, 전직, 계열 변경, 스킬 투자·환급·초기화, 단계 첫 품질 보상
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

    // Fishing: 통발 슬롯 확장 (슬롯당 2,000 × 통발 단계, '7. 밸런스 기획' 6.3)
    TRAP_SLOT_EXPAND,

    // World
    TREASURE,
    HOUSING_DECOR,
    // (추가 제안) 조선소 선박 구매·업그레이드, 해도 단계 보상, 주간 낚시 대회 순위 보상. 선박 수리는 Economy와 같은 REPAIR
    SHIP_PURCHASE,
    SHIP_UPGRADE,
    CHART_REWARD,
    TOURNAMENT_REWARD,

    // Boss
    BOSS_REWARD,

    // Shop (진주)
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
