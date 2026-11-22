package wiki.creeper.harbor.boss.data;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRole;

/**
 * PlayerComponent&lt;BossProfile&gt; (docs 20 10.3): preferred role and the location before going
 * to an instance (where to return). The current encounter ID is memory-only, so EncounterManager
 * keeps it separately, and the delivered marker lives in the player PDC harbor:boss_delivered.
 */
public record BossProfile(@Nullable BossRole preferred, @Nullable ReturnPoint returnPoint) {

    public static final BossProfile EMPTY = new BossProfile(null, null);

    /**
     * Location before leaving for an instance. On rejoining the main server the player is sent back
     * here and it is cleared.
     */
    public record ReturnPoint(
            String server, String world, double x, double y, double z, float yaw, float pitch) {}

    public BossProfile withPreferred(@Nullable BossRole role) {
        return new BossProfile(role, returnPoint);
    }

    public BossProfile withReturn(@Nullable ReturnPoint point) {
        return new BossProfile(preferred, point);
    }
}
