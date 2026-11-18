package wiki.creeper.harbor.season.api;

import java.util.Optional;
import org.bukkit.Bukkit;

/**
 * Season 서비스 찾기 도우미. Season이 Bukkit ServicesManager에 등록한 구현을 꺼낸다. Season에 필수 의존하는
 * 모듈(Collection·Boss·Quest …)은 enable 이후 언제든 쓸 수 있다.
 */
public final class SeasonServices {

    private SeasonServices() {}

    public static SeasonClock clock() {
        return require(SeasonClock.class);
    }

    public static WeeklyLimitService limits() {
        return require(WeeklyLimitService.class);
    }

    public static TitleService titles() {
        return require(TitleService.class);
    }

    public static RankingService rankings() {
        return require(RankingService.class);
    }

    public static HallOfFameService hallOfFame() {
        return require(HallOfFameService.class);
    }

    public static SeasonHookRegistry hooks() {
        return require(SeasonHookRegistry.class);
    }

    /** 선택 의존 모듈용: Season이 없으면 빈 Optional. */
    public static <T> Optional<T> find(Class<T> type) {
        return Optional.ofNullable(Bukkit.getServicesManager().load(type));
    }

    private static <T> T require(Class<T> type) {
        T service = Bukkit.getServicesManager().load(type);
        if (service == null) {
            throw new IllegalStateException("Harbor-Season이 켜져 있지 않습니다: " + type.getSimpleName());
        }
        return service;
    }
}
