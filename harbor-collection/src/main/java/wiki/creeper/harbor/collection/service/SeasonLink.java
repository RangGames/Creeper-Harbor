package wiki.creeper.harbor.collection.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.content.CollectionTitles;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.season.api.HallOfFameSubmission;
import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.SeasonHookContext;
import wiki.creeper.harbor.season.api.SeasonServices;
import wiki.creeper.harbor.season.api.TitleId;
import wiki.creeper.harbor.season.api.TitleService;

/**
 * Season integration (docs 20 4.4, 0.1): title grants (TitleService), hall of fame submission,
 * season transition hook. Season does not subscribe to other modules' events, so Collection calls
 * it directly.
 *
 * <ul>
 *   <li>Title grants are idempotent (unique per (uuid, title)). Failed (FAILED) grants stay in an
 *       in-memory queue and are retried on the next join or 5-minute cycle.
 *   <li>Milestones reached before Season existed (Phase 2a) are recomputed from the current
 *       collection state on first join and granted once (permanent flag collection/title_backfill).
 * </ul>
 */
public final class SeasonLink {

    static final String BACKFILL_FLAG = "collection/title_backfill";

    private final HarborApi api;
    private final Logger log;
    private final Map<UUID, Set<String>> retry = new HashMap<>();

    public SeasonLink(HarborApi api, Logger log) {
        this.api = api;
        this.log = log;
    }

    /** Title display name (reward track screen). The key itself if the key is wrong. */
    public net.kyori.adventure.text.Component titleName(String key) {
        try {
            return SeasonServices.titles().display(TitleId.of(key));
        } catch (IllegalArgumentException e) {
            return net.kyori.adventure.text.Component.text(key);
        }
    }

    /** Whether the player has the title (main-thread cache). */
    public boolean owns(UUID player, String key) {
        try {
            return SeasonServices.titles().has(player, TitleId.of(key));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Grants a title (main thread). key is a collection.yml title key (codex_25,
     * codex_region/harbor).
     */
    public CompletableFuture<TitleService.Grant> grant(UUID player, String key) {
        TitleId title;
        try {
            title = TitleId.of(key);
        } catch (IllegalArgumentException e) {
            log.warn("도감 칭호 키 형식이 틀렸습니다: {}", key);
            return CompletableFuture.completedFuture(TitleService.Grant.UNKNOWN_TITLE);
        }
        String ref = CollectionTitles.refId(api.infra().season().value(), key);
        return SeasonServices.titles()
                .grant(player, title, ref)
                .handle(
                        (g, e) -> {
                            TitleService.Grant result =
                                    e != null || g == null ? TitleService.Grant.FAILED : g;
                            switch (result) {
                                case FAILED ->
                                        retry.computeIfAbsent(player, u -> new LinkedHashSet<>())
                                                .add(key);
                                case UNKNOWN_TITLE ->
                                        // Region titles are defined only for regions in titles.yml
                                        // (water scales are already granted)
                                        log.debug("titles.yml에 없는 도감 칭호: {}", title);
                                default -> {
                                    Set<String> pending = retry.get(player);
                                    if (pending != null) {
                                        pending.remove(key);
                                    }
                                }
                            }
                            return result;
                        });
    }

    /** Retries grants left in the queue (join and periodic task). */
    public void retry(UUID player) {
        Set<String> pending = retry.remove(player);
        if (pending != null) {
            pending.forEach(
                    key -> {
                        var _ = grant(player, key);
                    });
        }
    }

    public void retryAll() {
        new ArrayList<>(retry.keySet()).forEach(this::retry);
    }

    /**
     * First time only: grants every title earned under the current collection. If it ends with all
     * owned (or undefined), sets a permanent flag so it does not run again.
     *
     * @param earned result of {@link CollectionTitles#earned}
     */
    public void backfill(UUID player, List<String> earned) {
        if (api.flags().hasPlayer(player, BACKFILL_FLAG)) {
            return;
        }
        List<CompletableFuture<TitleService.Grant>> grants = new ArrayList<>();
        earned.forEach(key -> grants.add(grant(player, key)));
        var _ =
                CompletableFuture.allOf(grants.toArray(new CompletableFuture<?>[0]))
                        .thenRun(
                                () -> {
                                    boolean done =
                                            grants.stream()
                                                    .map(CompletableFuture::join)
                                                    .noneMatch(g -> g == TitleService.Grant.FAILED);
                                    if (done) {
                                        var _ =
                                                api.flags()
                                                        .setPlayer(
                                                                player,
                                                                FlagService.Scope.PERM,
                                                                BACKFILL_FLAG);
                                    }
                                });
    }

    /**
     * Provisional hall of fame record when the all-league server #1 changes (called only by the
     * server that wrote the record).
     */
    public void submitBiggestFish(
            HarborId fish, UUID player, String name, double size, Instant caughtAt) {
        var _ =
                SeasonServices.hallOfFame()
                        .submit(
                                HallOfFameSubmission.biggestFish(
                                        fish, player, name, size, caughtAt))
                        .exceptionally(
                                e -> {
                                    log.warn(
                                            "명예의 전당 제출 실패 {} {}: {}",
                                            fish,
                                            player,
                                            String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    /**
     * Season transition hook (docs 20 4.4 "season transition"). Table cleanup is done by Season
     * reset_plan (codex_season archive_reset, aquarium archive_keep). The codex_season and
     * aquarium_stage modifiers live only in memory and disappear on the restart after the
     * transition; tank structures are re-placed with base_structure when the new season starts at
     * stage 0.
     */
    public void registerHook(Plugin plugin) {
        SeasonServices.hooks()
                .register(
                        plugin,
                        new SeasonHook() {
                            @Override
                            public String id() {
                                return "collection.codex";
                            }

                            @Override
                            public void onReset(SeasonHookContext ctx) {
                                ctx.report(
                                        "Collection: 시즌 도감·수족관 단계는 reset_plan으로 정리됨, 수조 구조물은 다음 시작 때 0단계");
                            }
                        });
    }
}
