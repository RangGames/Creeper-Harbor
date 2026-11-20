package wiki.creeper.harbor.fishing.trap;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.MailService;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 통발 자동 회수: 하루 한 번 본 서버 한 곳이 분산 잠금을 잡고, 7일 수확하지 않은 통발의 내용물을 Core MailService로 보낸 뒤 행과 블록을 지운다.
 * MailService가 없으면 보류한다. 메인 스레드에서 시작하고 DB·우편은 비동기.
 */
final class TrapRetrieval {

    private static final Duration RETRIEVE_LOCK_TTL = Duration.ofHours(20);
    private static final long RETRIEVE_CHECK_TICKS = 20L * 60 * 30;
    private static final int RETRIEVE_BATCH = 200;

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final TrapManager traps;
    private final TrapBlocks blocks;
    private final TrapHarvest harvest;

    TrapRetrieval(
            HarborApi api,
            Tasks tasks,
            Logger log,
            TrapManager traps,
            TrapBlocks blocks,
            TrapHarvest harvest) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.traps = traps;
        this.blocks = blocks;
        this.harvest = harvest;
    }

    void start() {
        var _ = tasks.timer(20L * 60, RETRIEVE_CHECK_TICKS, this::autoRetrieve);
    }

    /** 하루 한 번 (본 서버 한 곳): 7일 수확하지 않은 통발을 우편으로 회수한다. */
    private void autoRetrieve() {
        if (api.infra().role() != wiki.creeper.harbor.common.config.ServerRole.MAIN) {
            return;
        }
        Optional<MailService> mail = api.mail();
        if (mail.isEmpty()) {
            // 우편함(Economy)이 없으면 보류 (docs 20 3.8)
            return;
        }
        if (api.locks()
                .tryAcquire("harbor:lock:fishing-trap-retrieve", RETRIEVE_LOCK_TTL)
                .isEmpty()) {
            return;
        }
        int season = traps.season();
        Instant before = Instant.now().minus(Duration.ofDays(traps.balance().trapRetrieveDays()));
        var _ =
                api.database()
                        .async(c -> TrapRepository.stale(c, season, before, RETRIEVE_BATCH))
                        .whenCompleteAsync(
                                (rows, error) -> {
                                    if (error != null) {
                                        log.warn("통발 자동 회수 조회 실패: {}", String.valueOf(error));
                                        return;
                                    }
                                    if (!rows.isEmpty()) {
                                        log.info("통발 자동 회수 {}개", rows.size());
                                    }
                                    retrieveNext(mail.get(), new ArrayList<>(rows));
                                },
                                tasks.main());
    }

    /** 한 개씩 차례로: 항목마다 우편이 커밋된 뒤 contents에서 지운다 (중간에 꺼져도 중복 지급은 최대 1개). */
    private void retrieveNext(MailService mail, List<TrapRow> queue) {
        if (queue.isEmpty()) {
            return;
        }
        TrapRow row = queue.removeFirst();
        wiki.creeper.harbor.common.model.PlayerRef owner =
                new wiki.creeper.harbor.common.model.PlayerRef(
                        row.owner(),
                        Optional.ofNullable(Bukkit.getOfflinePlayer(row.owner()).getName())
                                .orElse("?"));
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (TrapCatch entry : row.contents()) {
            ItemStack stack = harvest.createCatch(owner, entry);
            if (stack == null) {
                continue;
            }
            String ref = "trap:" + row.id() + ":" + entry.uid();
            chain =
                    chain.thenCompose(v -> mail.sendItem(row.owner(), stack, "trap_retrieve", ref))
                            .thenCompose(
                                    v ->
                                            api.database()
                                                    .asyncVoid(
                                                            c ->
                                                                    TrapRepository.dropEntry(
                                                                            c,
                                                                            row.id(),
                                                                            entry.uid())));
        }
        List<ItemStack> extras = new ArrayList<>();
        api.content()
                .snapshot()
                .get(ItemDef.TYPE, row.item().id())
                .ifPresent(def -> extras.add(api.items().create(def, 1)));
        extras.addAll(harvest.refundBaitItems(row.bait()));
        CompletableFuture<Void> done =
                chain.thenCompose(
                                v -> api.database().async(c -> TrapRepository.delete(c, row.id())))
                        .thenCompose(
                                deleted -> {
                                    if (!Boolean.TRUE.equals(deleted)) {
                                        return CompletableFuture.completedFuture(null);
                                    }
                                    CompletableFuture<Void> rest =
                                            CompletableFuture.completedFuture(null);
                                    for (ItemStack extra : extras) {
                                        rest =
                                                rest.thenCompose(
                                                        v ->
                                                                mail.sendItem(
                                                                        row.owner(),
                                                                        extra,
                                                                        "trap_retrieve",
                                                                        "trapbox:" + row.id()));
                                    }
                                    return rest;
                                });
        var _ =
                done.whenCompleteAsync(
                        (v, error) -> {
                            if (error != null) {
                                log.warn(
                                        "통발 자동 회수 실패 ({}): {}",
                                        row.id(),
                                        String.valueOf(
                                                error instanceof CompletionException ce
                                                        ? ce.getCause()
                                                        : error));
                            } else {
                                blocks.demolish(row.world(), row.x(), row.y(), row.z(), row.id());
                            }
                            retrieveNext(mail, queue);
                        },
                        tasks.main());
    }
}
