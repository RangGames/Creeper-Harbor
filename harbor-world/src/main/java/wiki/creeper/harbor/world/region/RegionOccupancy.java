package wiki.creeper.harbor.world.region;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.world.WorldKit;

/**
 * Publishes per-server region occupancy to Redis and reads the network-wide totals (split out of
 * {@link RegionManager}).
 */
final class RegionOccupancy {

    private static final String OCCUPANCY_KEY = "harbor:world:occupancy";

    private final WorldKit kit;
    private final RegionManager regions;

    RegionOccupancy(WorldKit kit, RegionManager regions) {
        this.kit = kit;
        this.regions = regions;
    }

    CompletableFuture<Map<RegionId, Integer>> all() {
        long staleBefore = Instant.now().getEpochSecond() - kit.balance().occupancyStaleSeconds();
        return kit.tasks()
                .supplyAsync(
                        () -> {
                            Map<RegionId, Integer> result = new HashMap<>();
                            kit.api()
                                    .redis()
                                    .sync()
                                    .hgetall(OCCUPANCY_KEY)
                                    .forEach(
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
                                                    result.merge(
                                                            RegionId.parse(f[1]),
                                                            Integer.parseInt(v[0]),
                                                            Integer::sum);
                                                } catch (IllegalArgumentException e) {
                                                    // 형식이 틀린 항목은 건너뛴다
                                                }
                                            });
                            return result;
                        })
                .thenApplyAsync(Function.identity(), kit.tasks().main());
    }

    void publish() {
        String server = kit.api().infra().serverId();
        long now = Instant.now().getEpochSecond();
        Map<String, String> values = new HashMap<>();
        for (WorldIndex.Entry e : kit.index().regions()) {
            values.put(server + "|" + e.id(), regions.occupancy(e.id()) + "|" + now);
        }
        if (values.isEmpty()) {
            return;
        }
        var _ =
                kit.tasks()
                        .async(() -> kit.api().redis().sync().hset(OCCUPANCY_KEY, values))
                        .exceptionally(
                                e -> {
                                    kit.log().debug("체류 인원 발행 실패: {}", String.valueOf(e));
                                    return null;
                                });
    }
}
