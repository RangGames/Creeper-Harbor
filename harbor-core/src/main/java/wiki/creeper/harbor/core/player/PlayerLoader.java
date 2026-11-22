package wiki.creeper.harbor.core.player;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.PlayerComponent;

/**
 * Pre-login load (split out of {@link PlayerManager}): players and player_season, then every module
 * component in parallel with one shared deadline.
 */
final class PlayerLoader {

    private static final long LOAD_TIMEOUT_SECONDS = 5;

    private final InfraConfig infra;
    private final Database db;
    private final Map<String, PlayerComponent<?>> components;

    PlayerLoader(InfraConfig infra, Database db, Map<String, PlayerComponent<?>> components) {
        this.infra = infra;
        this.db = db;
        this.components = components;
    }

    PlayerState load(UUID uuid, String name)
            throws SQLException, InterruptedException, ExecutionException, TimeoutException {
        SeasonId season = infra.season();
        PlayerRepository.Row row = db.tx(c -> PlayerRepository.loadOrCreate(c, uuid, name, season));
        Map<String, Future<Object>> futures = new LinkedHashMap<>();
        for (PlayerComponent<?> component : components.values()) {
            futures.put(
                    component.id(),
                    db.executor()
                            .submit(
                                    () ->
                                            (Object)
                                                    db.tx(
                                                            c ->
                                                                    component
                                                                            .loader()
                                                                            .load(
                                                                                    c, uuid,
                                                                                    season))));
        }
        Map<String, Object> loaded = new HashMap<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LOAD_TIMEOUT_SECONDS);
        try {
            for (Map.Entry<String, Future<Object>> entry : futures.entrySet()) {
                long left = Math.max(0, deadline - System.nanoTime());
                loaded.put(entry.getKey(), entry.getValue().get(left, TimeUnit.NANOSECONDS));
            }
        } finally {
            futures.values().forEach(f -> f.cancel(true));
        }
        return new PlayerState(row, loaded);
    }
}
