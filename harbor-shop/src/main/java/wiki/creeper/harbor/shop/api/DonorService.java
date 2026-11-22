package wiki.creeper.harbor.shop.api;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.donor.DonorTier;

/**
 * Donor tier (docs 20 12.2). Subscription state is sourced from Shop's subscriptions, and the Redis
 * hash harbor:donor is always kept in sync (Proxy queue, World and Collection read it with Common
 * {@link DonorTier#lookup}). All perks are non-performance.
 */
public interface DonorService {

    /**
     * Status perks (only during the subscription).
     *
     * @param nameColor name color MiniMessage opening tag (empty string if none)
     * @param chatIcon chat icon glyph
     * @param title donor title ID
     */
    record Perks(
            DonorTier tier,
            String nameColor,
            String chatIcon,
            @Nullable String title,
            boolean queuePriority,
            int extraThemeSlots) {

        public static final Perks NONE = new Perks(DonorTier.NONE, "", "", null, false, 0);
    }

    /**
     * Tier of an online player (cache, main thread). NONE if not online (use DonorTier.lookup
     * then).
     */
    DonorTier tier(UUID player);

    Perks perks(UUID player);
}
