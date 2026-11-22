package wiki.creeper.harbor.fishing.session;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.core.api.FlagService;

/**
 * Personal fishing settings (/낚시 설정, docs 20 3.5). Stored in Core FlagService persistent player
 * flags (memory cache while online, writes go straight to the DB).
 *
 * @param lookMode direction input via camera rotation (default is the A/D movement keys).
 *     Automatically camera rotation while riding a vehicle
 * @param dialog show the catch result Dialog automatically
 * @param intensity presentation intensity 0 minimum · 1 normal · 2 maximum
 */
public record FishingSettings(boolean lookMode, boolean dialog, int intensity) {

    static final String DIRECTION = "fishing/settings/direction";
    static final String DIALOG = "fishing/settings/dialog";
    static final String INTENSITY = "fishing/settings/intensity";

    public static final FishingSettings DEFAULT = new FishingSettings(false, true, 2);

    public static FishingSettings of(FlagService flags, UUID player) {
        boolean look = flags.playerValue(player, DIRECTION).map("look"::equals).orElse(false);
        boolean dialog = flags.playerValue(player, DIALOG).map(v -> !v.equals("off")).orElse(true);
        int intensity =
                flags.playerValue(player, INTENSITY)
                        .map(
                                v -> {
                                    try {
                                        return Math.clamp(Integer.parseInt(v), 0, 2);
                                    } catch (NumberFormatException e) {
                                        return DEFAULT.intensity();
                                    }
                                })
                        .orElse(DEFAULT.intensity());
        return new FishingSettings(look, dialog, intensity);
    }

    /** Saves. When complete (main thread), the cache is updated too. */
    static CompletableFuture<Void> save(FlagService flags, UUID player, FishingSettings s) {
        return CompletableFuture.allOf(
                flags.setPlayer(
                        player, FlagService.Scope.PERM, DIRECTION, s.lookMode() ? "look" : "move"),
                flags.setPlayer(player, FlagService.Scope.PERM, DIALOG, s.dialog() ? "on" : "off"),
                flags.setPlayer(
                        player, FlagService.Scope.PERM, INTENSITY, String.valueOf(s.intensity())));
    }
}
