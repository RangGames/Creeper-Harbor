package wiki.creeper.harbor.fishing.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Quality;

/**
 * Catch presentation cosmetic (docs 20 3.2, proposed). Shop registers it via {@link
 * FishingService#registerPresentation} to change the equipped catch presentation (light pillar
 * color, firework shape, sound). Fishing calls it before playing the grade preset; returning true
 * skips the preset's light pillar, firework and sound (chat text, announcements and titles are
 * kept). Called on the main thread.
 */
@FunctionalInterface
public interface CatchPresentationOverride {

    /**
     * @return true if it replaced the default presentation
     */
    boolean present(Player player, CatchPresentation catchInfo);

    /**
     * Catch information.
     *
     * @param at presentation location (player location)
     * @param intensity player's presentation intensity setting (0 minimum, 1 normal, 2 maximum)
     */
    record CatchPresentation(
            FishDef fish,
            double sizeCm,
            Quality quality,
            boolean firstCatch,
            Location at,
            int intensity) {

        public CatchPresentation {
            at = at.clone();
        }
    }
}
