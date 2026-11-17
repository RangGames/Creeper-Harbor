package wiki.creeper.harbor.economy.content;

import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.Quality;

/** balance.yml economy 절 (docs 20 7.3). 값이 없으면 '7. 밸런스 기획' 6장 값 또는 제안값. */
public record EconomyBalance(Balance balance) {

    /** 품질 배율 (제안값 1.0 / 1.2 / 1.5 / 2.5 / 5.0). */
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
}
