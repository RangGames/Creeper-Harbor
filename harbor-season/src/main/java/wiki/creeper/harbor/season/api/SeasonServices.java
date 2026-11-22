package wiki.creeper.harbor.season.api;

import java.util.Optional;
import org.bukkit.Bukkit;

/**
 * Season service lookup helper. Retrieves the implementations Season registered with the Bukkit
 * ServicesManager. Modules with a hard dependency on Season (Collection, Boss, Quest ...) can use
 * them any time after enable.
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

    /** For soft-dependent modules: empty Optional if Season is not present. */
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
