package wiki.creeper.harbor.economy.service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.core.api.HarborApi;

/**
 * Market engine inputs kept in Redis (split out of {@link MarketEngine}). Call from a virtual
 * thread (Redis round trips).
 *
 * <ul>
 *   <li>Region occupancy history for the baseline S₀ (docs 20 7.4 step 3: "Σ over the last 24 hours
 *       of each hour's players staying in the fish's region × 80 × 0.85 × expected share"). World
 *       publishes per-server occupancy to the Redis hash harbor:world:occupancy (field
 *       server|region, value count|epoch seconds) every minute; Economy does not depend on World
 *       (docs 20 0.3), so the lock holder samples that hash once per hourly computation into
 *       harbor:economy:occupancy:&lt;hour epoch seconds&gt; (proposed key, kept window + 2 hours).
 *   <li>Admin price pins with an expiry ({@code /harbor market set <fish> <coef> [hours]}, proposed
 *       key harbor:economy:market_pin, field fish ID, value coef|expiry epoch seconds).
 * </ul>
 */
final class MarketInputs {

    private static final String WORLD_OCCUPANCY = "harbor:world:occupancy";
    private static final String HOURLY = "harbor:economy:occupancy:";
    private static final String PINS = "harbor:economy:market_pin";

    /** World entries older than this count as a stopped server (World's stale_s default). */
    private static final long WORLD_STALE_SECONDS = 180;

    private final HarborApi api;
    private final Logger log;

    MarketInputs(HarborApi api, Logger log) {
        this.api = api;
        this.log = log;
    }

    // ------------------------------------------------------------------ occupancy

    /**
     * Samples the current network-wide region occupancy into this hour's bucket, then returns the
     * player-hours per region (region ID → Σ hourly occupancy) over the window ending at this hour.
     * Hours without a sample (right after deployment, Redis loss) are filled by scaling the sampled
     * hours up to the window (proposed). Empty when World has never published (World not
     * installed): the caller falls back to the network online count.
     */
    Optional<Map<String, Double>> playerHours(Instant hour, int windowHours) {
        try {
            var redis = api.redis().sync();
            Map<String, Integer> now = currentOccupancy(redis.hgetall(WORLD_OCCUPANCY));
            String bucket = HOURLY + hour.getEpochSecond();
            if (!now.isEmpty()) {
                Map<String, String> values = new HashMap<>();
                now.forEach((region, count) -> values.put(region, String.valueOf(count)));
                redis.hset(bucket, values);
            } else {
                // Nobody in any region (or World stopped): still mark the hour as sampled
                redis.hset(bucket, "_", "0");
            }
            redis.expire(bucket, Duration.ofHours(windowHours + 2L).toSeconds());
            Map<String, Double> sums = new HashMap<>();
            int samples = 0;
            for (int i = 0; i < windowHours; i++) {
                Map<String, String> h =
                        redis.hgetall(HOURLY + hour.minus(Duration.ofHours(i)).getEpochSecond());
                if (h.isEmpty()) {
                    continue;
                }
                samples++;
                h.forEach(
                        (region, raw) -> {
                            if (region.equals("_")) {
                                return;
                            }
                            try {
                                sums.merge(region, (double) Integer.parseInt(raw), Double::sum);
                            } catch (NumberFormatException e) {
                                // Skip malformed entries
                            }
                        });
            }
            if (samples == 0
                    || (now.isEmpty() && sums.isEmpty() && redis.exists(WORLD_OCCUPANCY) == 0)) {
                return Optional.empty();
            }
            double scale = (double) windowHours / samples;
            sums.replaceAll((region, v) -> v * scale);
            return Optional.of(sums);
        } catch (RuntimeException e) {
            log.warn("지역 체류 인원을 읽지 못해 접속 인원으로 계산합니다: {}", String.valueOf(e.getMessage()));
            return Optional.empty();
        }
    }

    /** Sum over servers of fresh World entries, region ID → players. */
    private static Map<String, Integer> currentOccupancy(Map<String, String> raw) {
        long staleBefore = Instant.now().getEpochSecond() - WORLD_STALE_SECONDS;
        Map<String, Integer> result = new HashMap<>();
        raw.forEach(
                (field, value) -> {
                    String[] f = field.split("\\|", 2);
                    String[] v = value.split("\\|", 2);
                    if (f.length != 2 || v.length != 2) {
                        return;
                    }
                    try {
                        if (Long.parseLong(v[1]) < staleBefore) {
                            return;
                        }
                        result.merge(f[1], Integer.parseInt(v[0]), Integer::sum);
                    } catch (NumberFormatException e) {
                        // Skip malformed entries
                    }
                });
        return result;
    }

    // ------------------------------------------------------------------ pins

    /** Stores a pin until the expiry (virtual thread). */
    void pin(FishId fish, double coef, Instant until) {
        api.redis().sync().hset(PINS, fish.toString(), coef + "|" + until.getEpochSecond());
    }

    /** Removes a pin (virtual thread). */
    void unpin(FishId fish) {
        api.redis().sync().hdel(PINS, fish.toString());
    }

    /** Pins still in force (fish ID → coef); expired ones are removed. */
    Map<String, Double> activePins() {
        Map<String, Double> result = new HashMap<>();
        try {
            var redis = api.redis().sync();
            long now = Instant.now().getEpochSecond();
            redis.hgetall(PINS)
                    .forEach(
                            (fish, value) -> {
                                String[] v = value.split("\\|", 2);
                                try {
                                    if (v.length == 2 && Long.parseLong(v[1]) > now) {
                                        result.put(fish, Double.parseDouble(v[0]));
                                    } else {
                                        redis.hdel(PINS, fish);
                                    }
                                } catch (NumberFormatException e) {
                                    redis.hdel(PINS, fish);
                                }
                            });
        } catch (RuntimeException e) {
            log.warn("시세 고정값을 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
        }
        return result;
    }
}
