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
import wiki.creeper.harbor.collection.content.CodexTitles;
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
 * Season 연동 (docs 20 4.4, 0.1): 칭호 지급(TitleService), 명예의 전당 제출, 시즌 전환 훅. Season은 다른 모듈 이벤트를 구독하지
 * 않으므로 Collection이 직접 부른다.
 *
 * <ul>
 *   <li>칭호 지급은 멱등이다 ((uuid, 칭호) 유니크). 실패(FAILED)한 지급은 메모리 대기열에 두고 다음 접속·5분 주기에 다시 시도한다.
 *   <li>Season이 생기기 전(Phase 2a)에 달성한 마일스톤은 첫 접속 때 지금 도감 상태로 다시 계산해 한 번 지급한다 (영구 플래그
 *       collection/title_backfill).
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

    /** 칭호 표시 이름 (보상 트랙 화면). 키가 틀렸으면 키 그대로. */
    public net.kyori.adventure.text.Component titleName(String key) {
        try {
            return SeasonServices.titles().display(TitleId.of(key));
        } catch (IllegalArgumentException e) {
            return net.kyori.adventure.text.Component.text(key);
        }
    }

    /** 칭호를 가지고 있는지 (메인 스레드 캐시). */
    public boolean owns(UUID player, String key) {
        try {
            return SeasonServices.titles().has(player, TitleId.of(key));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 칭호를 준다 (메인 스레드). key는 codex.yml 칭호 키 (codex_25, codex_region/harbor). */
    public CompletableFuture<TitleService.Grant> grant(UUID player, String key) {
        TitleId title;
        try {
            title = TitleId.of(key);
        } catch (IllegalArgumentException e) {
            log.warn("도감 칭호 키 형식이 틀렸습니다: {}", key);
            return CompletableFuture.completedFuture(TitleService.Grant.UNKNOWN_TITLE);
        }
        String ref = CodexTitles.refId(api.infra().season().value(), key);
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
                                        // 지역 칭호는 titles.yml에 있는 지역만 정의된다 (물비늘은 이미 지급)
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

    /** 대기열에 남은 지급을 다시 시도한다 (접속·주기 작업). */
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
     * 처음 한 번: 지금 도감으로 받아야 하는 칭호를 모두 지급한다. 모두 보유(또는 정의 없음)로 끝나면 영구 플래그를 세워 다시 하지 않는다.
     *
     * @param earned {@link CodexTitles#earned} 결과
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

    /** 전체 리그 서버 1위가 바뀌었을 때 명예의 전당 잠정 기록 (기록을 쓴 서버만 부른다). */
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
     * 시즌 전환 훅 (docs 20 4.4 '시즌 전환'). 테이블 정리는 Season reset_plan이 한다 (codex_season archive_reset, 수족관
     * archive_keep). codex_season·aquarium_stage 보정자는 메모리에만 있어 전환 뒤 재시작으로 사라지고, 수조 구조물은 새 시즌 0단계로
     * 시작할 때 base_structure로 다시 붙는다.
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
