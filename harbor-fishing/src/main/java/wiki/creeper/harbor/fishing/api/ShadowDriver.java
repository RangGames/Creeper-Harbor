package wiki.creeper.harbor.fishing.api;

import org.bukkit.Location;
import wiki.creeper.harbor.fishing.api.event.FishingCastEvent;

/**
 * SPI for taking over the shadow while waiting (docs 20 3.2/3.4 Wildlife integration). Wildlife
 * hands it over via {@link FishingCastEvent#claimShadow} (or {@link FishingService#claimShadow}).
 * The direction is Wildlife → Fishing only, so Fishing never calls Wildlife directly, only this
 * interface.
 *
 * <p>Order: {@link #approach} (approaches the bobber during the wait) → {@link #bite} (bite moment,
 * the individual dives) → the Fishing shadow takes over at the same spot and runs the minigame →
 * {@link #release} (releases the individual when the session ends). If {@link #isValid} becomes
 * false while waiting, Fishing immediately switches to the default shadow and calls {@link
 * #release}. All methods are called on the main thread.
 */
public interface ShadowDriver {

    /**
     * Starts approaching the bobber.
     *
     * @param hook bobber location
     * @param ticks ticks left until the bite (it only needs to reach the bobber within this time)
     */
    void approach(Location hook, int ticks);

    /**
     * The bite moment. Makes the individual dive underwater. Fishing continues drawing the minigame
     * shadow from this location.
     */
    void bite();

    /** Current location of the individual (shadow handover point). */
    Location location();

    /** Whether the individual is still alive and can be driven (false on chunk unload or death). */
    boolean isValid();

    /**
     * The session ended. Releases the individual (it returns to its normal behaviour).
     *
     * @param caught whether that individual's species was actually caught (Wildlife decides whether
     *     to remove the individual)
     */
    void release(boolean caught);
}
