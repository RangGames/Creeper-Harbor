package wiki.creeper.harbor.economy.content;

import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.Quality;

/** balance.yml economy 절 (docs 20 7.3). 값이 없으면 '7. 밸런스 기획' 6장 값 또는 제안값. */
public record EconomyBalance(Balance balance) {

    /** 품질 배율 (D2 확정값 1.0 / 1.2 / 1.5 / 2.5 / 5.0, docs 20 0.6). */
    public double qualityMult(Quality quality) {
        double fallback =
                switch (quality.stars()) {
                    case 1 -> 1.0;
                    case 2 -> 1.2;
                    case 3 -> 1.5;
                    case 4 -> 2.5;
                    default -> 5.0;
                };
        return balance.number("economy.quality_mult." + quality.stars(), fallback);
    }

    /** 지역 계수 (공허의 바다 0.65). 키는 지역 ID의 마지막 마디. */
    public double regionFactor(String region) {
        return balance.number("economy.region_factor." + region, 1.0);
    }

    public long legendCap() {
        return (long) balance.number("economy.legend_cap", 3000);
    }

    public double marketK() {
        return balance.number("economy.market.slope", 0.35);
    }

    public double marketMin() {
        return balance.number("economy.market.min", 0.5);
    }

    public double marketMax() {
        return balance.number("economy.market.max", 1.2);
    }

    public int marketWindowHours() {
        return balance.integer("economy.market.window_h", 24);
    }

    public double s0Floor() {
        return balance.number("economy.market.s0_floor", 20);
    }

    public double catchesPerHour() {
        return balance.number("economy.market.catches_per_hour", 80);
    }

    public double sellRatio() {
        return balance.number("economy.market.sell_ratio", 0.85);
    }

    public double repairRate() {
        return balance.number("economy.repair.rate", 0.03);
    }

    public double repairBrokenRate() {
        return balance.number("economy.repair.broken_rate", 0.10);
    }

    // ------------------------------------------------------------------ 시세 (추가)

    /** 일괄 판매 미끄러짐 기준 (같은 어종을 한 번에 이보다 많이 팔면 적용, 제안값 32). */
    public int bulkThreshold() {
        return balance.integer("economy.bulk.threshold", 32);
    }

    /** 시세 갱신이 이 시간 넘게 없으면 관리자에게 알린다 (docs 20 7.4, 3시간). */
    public int marketStaleHours() {
        return balance.integer("economy.market.stale_h", 3);
    }

    /** 큰손 거래 최대 마릿수 (docs 20 7.4, 64). */
    public int bigDealMax() {
        return balance.integer("economy.big_deal.max", 64);
    }

    /** 큰손 거래 고정 시세 계수 (1.0). */
    public double bigDealCoef() {
        return balance.number("economy.big_deal.coef", 1.0);
    }

    // ------------------------------------------------------------------ 경매 ('7. 밸런스 기획' 6.3·6.5)

    public double auctionListFee() {
        return balance.number("economy.auction.list_fee", 0.01);
    }

    public double auctionSaleFee() {
        return balance.number("economy.auction.sale_fee", 0.05);
    }

    public double auctionMinRatio() {
        return balance.number("economy.auction.min_ratio", 0.5);
    }

    public double auctionMaxRatio() {
        return balance.number("economy.auction.max_ratio", 10);
    }

    public int auctionDurationHours() {
        return balance.integer("economy.auction.duration_h", 48);
    }

    /** 평판 등급별 등록 슬롯 (5 / 10 / 15, 등급 대응은 제안값). */
    public int auctionSlots(int reputationTier) {
        int fallback = reputationTier >= 5 ? 15 : reputationTier >= 3 ? 10 : 5;
        return balance.integer("economy.auction.slots." + reputationTier, fallback);
    }

    public int auctionNewAccountHours() {
        return balance.integer("economy.auction.new_account_h", 5);
    }

    /** 신화 어종 경매 기준가 (D12, 6,000G). */
    public long auctionMythicRef() {
        return (long) balance.number("economy.auction.mythic_ref", 6000);
    }

    /** 등록·취소 가능 반경 (경매 게시판 기준, 8블록). */
    public double auctionBoardRadius() {
        return balance.number("economy.auction.board_radius", 8);
    }

    /** 분류별 첫 페이지 캐시 (30초). */
    public int auctionCacheSeconds() {
        return balance.integer("economy.auction.cache_s", 30);
    }

    // ------------------------------------------------------------------ 제작

    /** /제작이 여는 스테이션 반경 (제안값 6). */
    public double craftStationRadius() {
        return balance.number("economy.craft.station_radius", 6);
    }

    /** 재료 절약 상한 (제안값 0.5, 절약 보정이 아무리 커도 재료의 절반은 든다). */
    public double craftMaxSave() {
        return balance.number("economy.craft.max_save", 0.5);
    }

    // ------------------------------------------------------------------ 부정 거래 감시 (docs 20 7.4, 모두
    // 제안값)

    public FraudRules fraud() {
        return new FraudRules(
                balance.number("economy.fraud.linked_score", 60),
                balance.integer("economy.fraud.pair_window_d", 7),
                balance.integer("economy.fraud.pair_count", 3),
                balance.number("economy.fraud.pair_score", 40),
                balance.number("economy.fraud.outlier_ratio", 5),
                balance.number("economy.fraud.outlier_score", 30),
                balance.number("economy.fraud.wash_ratio", 0.6),
                balance.number("economy.fraud.wash_score", 20),
                balance.number("economy.fraud.trap_alt_score", 30),
                balance.number("economy.fraud.flag_score", 30),
                balance.number("economy.fraud.hold_score", 70),
                balance.integer("economy.fraud.hold_h", 24),
                balance.bool("economy.fraud.auto_release", true),
                balance.number("economy.fraud.index_min", 0.8),
                balance.number("economy.fraud.index_max", 2.0));
    }

    /**
     * 부정 거래 점수 규칙.
     *
     * @param linkedScore 같은 IP(공유 IP 제외)로 연결된 두 계정 사이 거래
     * @param pairCount 같은 두 사람 사이 거래가 창 안에 이 횟수 이상이면 (이번 거래 포함)
     * @param outlierRatio 기준가의 이 배수 이상 낙찰
     * @param washRatio 연결 계정끼리 기준가의 이 배수 이하 거래 (헐값 넘기기)
     * @param trapAltScore 연결 계정끼리 통발 어종 거래 ('12. 월드 · 하우징 · 통발' 5장)
     * @param flagScore 이 점수 이상이면 trade_flag 기록·관리자 알림
     * @param holdScore 이 점수 이상이면 판매 대금 보류
     * @param autoRelease 보류 시간이 지나면 자동 해제 (거짓이면 관리자 해제 전까지 보류, docs 20 7.8 열린 질문)
     * @param indexMin 경매 희귀 시세 지수 경고 하한 ('7. 밸런스 기획' 11장)
     */
    public record FraudRules(
            double linkedScore,
            int pairWindowDays,
            int pairCount,
            double pairScore,
            double outlierRatio,
            double outlierScore,
            double washRatio,
            double washScore,
            double trapAltScore,
            double flagScore,
            double holdScore,
            int holdHours,
            boolean autoRelease,
            double indexMin,
            double indexMax) {}
}
