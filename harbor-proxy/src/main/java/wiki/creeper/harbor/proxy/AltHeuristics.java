package wiki.creeper.harbor.proxy;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Multi-account decision rules (docs 20 1.4 AltDetector). Pure logic without server API or DB.
 *
 * <ul>
 *   <li>Other accounts that joined from the same ip_hash within window_days (30 days, proposed
 *       value) are linked-account candidates
 *   <li>If the account count for that IP (including self) is at least alert_accounts (3, proposed
 *       value) and it is not a shared IP, staff are alerted
 *   <li>No automatic bans (avoids false positives on internet cafe shared IPs)
 *   <li>For the same IP, an alert is resent only when the account count grows (not on every login)
 * </ul>
 */
final class AltHeuristics {

    private AltHeuristics() {}

    /**
     * Link candidates among accounts from the same IP, excluding self (deduplicated, order kept).
     */
    static Set<UUID> partners(UUID self, Collection<UUID> sameIp) {
        Set<UUID> result = new LinkedHashSet<>(sameIp);
        result.remove(self);
        return result;
    }

    /** Account count for that IP (including self). */
    static int accounts(UUID self, Collection<UUID> sameIp) {
        return partners(self, sameIp).size() + 1;
    }

    /**
     * Whether to send an alert.
     *
     * @param accounts account count for that IP (including self)
     * @param sharedIp whether registered in ip_shared
     * @param lastAlerted account count at the last alert for this IP (0 if none)
     * @param threshold alert threshold account count
     */
    static boolean shouldAlert(int accounts, boolean sharedIp, int lastAlerted, int threshold) {
        return !sharedIp && accounts >= threshold && accounts > lastAlerted;
    }
}
