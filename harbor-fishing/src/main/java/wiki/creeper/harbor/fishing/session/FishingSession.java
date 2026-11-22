package wiki.creeper.harbor.fishing.session;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.fishing.api.SessionState;

/** One player's fishing session (main thread only). */
final class FishingSession {

    /** Mythic server count reservation (DB counter, waits until confirmed). */
    static final class MythicHold {
        final String eventId;
        final String fishId;
        boolean confirmed;
        boolean released;

        MythicHold(String eventId, String fishId) {
            this.eventId = eventId;
            this.fishId = fishId;
        }
    }

    /**
     * Rolls of casts that ended before a result (kept 60 seconds, reused as is when cast again with
     * the same region and bait). Kept separately per region and bait combination: with only one,
     * alternating baits on every cast discarded the previous roll and rerolled, defeating the
     * reroll prevention.
     */
    record PendingRoll(
            RegionId region,
            @Nullable ItemId bait,
            FishDef fish,
            List<FishDef> legends,
            @Nullable MythicHold mythic,
            Instant expiresAt) {}

    SessionState state = SessionState.IDLE;
    @Nullable FishDef fish;
    @Nullable RegionId region;
    @Nullable ItemId bait;
    List<FishDef> legends = List.of();
    boolean pityCounted;
    long biteAtNanos;
    final Map<String, PendingRoll> pendings = new HashMap<>();

    static String pendingKey(RegionId region, @Nullable ItemId bait) {
        return region + "|" + (bait == null ? "-" : bait.toString());
    }

    @Nullable Minigame minigame;
    @Nullable BossBar bar;
    int missedBig;

    // CAST → WAITING
    @Nullable FishHook hook;
    @Nullable Location hookLoc;
    int waitTicks;
    int castTicks;
    boolean school;
    @Nullable MythicHold mythic;
    @Nullable ShadowControl shadow;

    // Minigame
    boolean legendBait;
    float baseYaw;
    int lastPhase = 1;
    boolean borderWarn;

    /** Reel looping sound (while reeling with Shift, "15. Sound" fishing.reel_loop). */
    HarborSounds.Loop reel = HarborSounds.Loop.NONE;

    Minigame.@Nullable Pattern lastActive;
    FishingSettings settings = FishingSettings.DEFAULT;

    // Latest input (PlayerInputEvent)
    boolean inputSneak;
    boolean inputLeft;
    boolean inputRight;

    // Admin forced roll (/harbor fishing force)
    @Nullable FishDef forcedFish;
    @Nullable Quality forcedQuality;

    void clearCast() {
        state = SessionState.IDLE;
        fish = null;
        region = null;
        bait = null;
        legends = List.of();
        pityCounted = false;
        minigame = null;
        hook = null;
        hookLoc = null;
        waitTicks = 0;
        castTicks = 0;
        school = false;
        mythic = null;
        shadow = null;
        legendBait = false;
        lastPhase = 1;
        borderWarn = false;
        lastActive = null;
    }
}
