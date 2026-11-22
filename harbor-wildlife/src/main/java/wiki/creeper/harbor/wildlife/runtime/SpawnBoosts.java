package wiki.creeper.harbor.wildlife.runtime;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;

/**
 * Spawn density multipliers (sea event great migration, WildlifeService.boost). Multiplied into the
 * region target count and species weight. Main thread only.
 */
final class SpawnBoosts {

    private record Boost(
            String source,
            @Nullable RegionId region,
            Set<FishId> fish,
            double mult,
            Instant until) {}

    private final Map<String, Boost> boosts = new HashMap<>();

    /**
     * Great migration: target species density ×wildlifeBoost (multiplier set by World, balance
     * default ×2 if absent).
     */
    void seaEventStarted(ActiveSeaEvent e) {
        if (e.phase() != ActiveSeaEvent.Phase.ACTIVE || e.wildlifeBoost() <= 1) {
            return;
        }
        Set<FishId> fish = new HashSet<>();
        e.fishEffects().stream()
                .filter(fx -> fx.type().equals("species_weight"))
                .forEach(
                        fx -> {
                            try {
                                fish.add(FishId.parse(fx.fish()));
                            } catch (IllegalArgumentException ignored) {
                                // invalid species IDs are caught by World's content checks
                            }
                        });
        boosts.put(
                "event:" + e.runId(),
                new Boost(
                        "event:" + e.runId(),
                        e.region(),
                        Set.copyOf(fish),
                        e.wildlifeBoost(),
                        e.endsAt()));
    }

    void seaEventEnded(ActiveSeaEvent e) {
        boosts.remove("event:" + e.runId());
    }

    void put(String source, RegionId region, Set<FishId> fish, double multiplier, Instant until) {
        boosts.put(source, new Boost(source, region, Set.copyOf(fish), multiplier, until));
    }

    void remove(String source) {
        boosts.remove(source);
    }

    /** Clears expired multipliers. */
    void prune() {
        Instant now = Instant.now();
        boosts.values().removeIf(b -> b.until().isBefore(now));
    }

    double target(RegionId region) {
        double m = 1;
        Instant now = Instant.now();
        for (Boost b : boosts.values()) {
            if (b.until().isAfter(now) && (b.region() == null || b.region().equals(region))) {
                m = Math.max(m, b.mult());
            }
        }
        return m;
    }

    double weight(RegionId region, FishId fish) {
        double m = 1;
        Instant now = Instant.now();
        for (Boost b : boosts.values()) {
            if (b.until().isAfter(now)
                    && (b.region() == null || b.region().equals(region))
                    && (b.fish().isEmpty() || b.fish().contains(fish))) {
                m = Math.max(m, b.mult());
            }
        }
        return m;
    }

    int count() {
        return boosts.size();
    }
}
