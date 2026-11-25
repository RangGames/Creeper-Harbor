package wiki.creeper.harbor.fishing.sea;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.fishing.api.SeaEventMessage;
import wiki.creeper.harbor.fishing.api.SeaEventMessage.FishEffect;
import wiki.creeper.harbor.fishing.roll.RollContext.MythicWindow;

/**
 * Active sea events and their fish_effects (docs 20 3.2 harbor:event). Receives start/end via
 * harbor:event messages and, at roll time, returns the species multipliers and mythic windows that
 * apply to this player (region). Continues with the last state if Redis disconnects, and each event
 * ends on its own once endsAt passes. Without an end time it ends after the default duration
 * (proposed value 2 hours). Main thread only, no server API (unit tests).
 */
public final class SeaEventBook {

    /** Default duration (START without endsAt, proposed value). */
    public static final Duration DEFAULT_DURATION = Duration.ofHours(2);

    /** An active event. region null means the whole server. */
    public record Active(
            String eventId, @Nullable RegionId region, Instant endsAt, List<FishEffect> effects) {

        boolean covers(RegionId at) {
            return region == null || region.equals(at);
        }
    }

    private final Map<String, Active> active = new LinkedHashMap<>();

    /**
     * Applies a message. A malformed region ID is ignored rather than treated as the whole server.
     */
    public void apply(SeaEventMessage message, Instant now) {
        if (message.state() == SeaEventMessage.State.END) {
            active.remove(message.eventId());
            return;
        }
        RegionId region = null;
        String raw = message.region();
        if (raw != null && !raw.isBlank()) {
            try {
                region = RegionId.parse(raw);
            } catch (IllegalArgumentException e) {
                return;
            }
        }
        Instant ends = message.endsAt();
        active.put(
                message.eventId(),
                new Active(
                        message.eventId(),
                        region,
                        ends == null ? now.plus(DEFAULT_DURATION) : ends,
                        message.fishEffects()));
    }

    /** Removes finished events and returns the active ones. */
    public List<Active> active(Instant now) {
        active.values().removeIf(a -> !now.isBefore(a.endsAt()));
        return List.copyOf(active.values());
    }

    /** Whether any active sea event covers this region (event waters). */
    public boolean covers(RegionId region, Instant now) {
        for (Active a : active(now)) {
            if (a.covers(region)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> ids(Instant now) {
        active(now);
        return Set.copyOf(active.keySet());
    }

    /**
     * Species multipliers for this region (multiplied across events). Keys are species ID strings.
     */
    public Map<String, Double> speciesMult(RegionId region, Instant now) {
        Map<String, Double> result = new HashMap<>();
        for (Active a : active(now)) {
            if (!a.covers(region)) {
                continue;
            }
            for (FishEffect e : a.effects()) {
                if (FishEffect.SPECIES_WEIGHT.equals(e.type()) && e.mult() > 0) {
                    result.merge(normalize(e.fish()), e.mult(), (x, y) -> x * y);
                }
            }
        }
        return result;
    }

    /**
     * Mythic windows open in this region. Species missing from content or not mythic are skipped.
     */
    public List<MythicWindow> mythicWindows(
            RegionId region, Instant now, Function<String, Optional<FishDef>> fish) {
        List<MythicWindow> result = new ArrayList<>();
        for (Active a : active(now)) {
            if (!a.covers(region)) {
                continue;
            }
            for (FishEffect e : a.effects()) {
                if (!FishEffect.MYTHIC.equals(e.type()) || e.cap() <= 0) {
                    continue;
                }
                fish.apply(normalize(e.fish()))
                        .ifPresent(
                                def ->
                                        result.add(
                                                new MythicWindow(
                                                        a.eventId(), def, e.chance(), e.cap())));
            }
        }
        return result;
    }

    /** Prefixes IDs without a namespace (fish/…) with harbor:. */
    static String normalize(String fish) {
        return fish.contains(":") ? fish : "harbor:" + fish;
    }
}
