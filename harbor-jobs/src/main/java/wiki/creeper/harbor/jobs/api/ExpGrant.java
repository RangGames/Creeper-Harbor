package wiki.creeper.harbor.jobs.api;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;

/**
 * Experience grant request (docs 20 2.2).
 *
 * @param base base EXP computed by the source module (Fishing: grade base EXP × region multiplier ×
 *     first catch multiplier)
 * @param region for region decay (FISHING only)
 * @param refId reference ID for records
 */
public record ExpGrant(
        ExpSource source, double base, @Nullable RegionId region, @Nullable String refId) {

    public static ExpGrant of(ExpSource source, double base) {
        return new ExpGrant(source, base, null, null);
    }
}
