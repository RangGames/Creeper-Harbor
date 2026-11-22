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
import wiki.creeper.harbor.common.db.SqlConsumer;
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
            // 우편과 항목 삭제를 한 트랜잭션으로, 그 항목이 아직 통발에 있을 때만 (조회 뒤 주인이 직접 수확했으면 건너뛴다: 예전에는
            // 조회 때의 목록으로 보내서 두 번 받았다)
            SqlConsumer entryMail = mail.sendItemInTx(row.owner(), stack, "trap_retrieve", ref);
            chain =
                    chain.thenCompose(
                            v ->
                                    api.database()
                                            .async(
                                                    c -> {
                                                        Optional<TrapRow> now =
                                                                TrapRepository.byId(
                                                                        c, row.id(), true);
                                                        boolean present =
                                                                now.isPresent()
                                                                        && now
                                                                                .get()
                                                                                .contents()
                                                                                .stream()
                                                                                .anyMatch(
                                                                                        e ->
                                                                                                e.uid()
                                                                                                        .equals(
                                                                                                                entry
                                                                                                                        .uid()));
                                                        if (!present) {
                                                            return false;
                                                        }
                                                        entryMail.accept(c);
                                                        TrapRepository.dropEntry(
                                                                c, row.id(), entry.uid());
                                                        return true;
                                                    })
                                            .thenAccept(
                                                    sent -> {
                                                        if (sent) {
                                                            mail.notifyArrived(row.owner());
                                                        }
                                                    }));
        }
        List<ItemStack> extras = new ArrayList<>();
        api.content()
                .snapshot()
                .get(ItemDef.TYPE, row.item().id())
                .ifPresent(def -> extras.add(api.items().create(def, 1)));
        extras.addAll(harvest.refundBaitItems(row.bait()));
        // 통발 아이템·미끼 환불 우편과 행 삭제를 한 트랜잭션으로 (중간에 서버가 죽어도 사라지거나 두 번 가지 않는다). 우편 참조 ID는 항목마다
        // 달라야 한다 (같으면 우편함 유니크 키 때문에 두 번째부터 버려진다). 그 사이 주인이 미끼를 넣거나 통발을 치웠으면 이번에는 건너뛴다
        List<SqlConsumer> extraMails = new ArrayList<>();
        for (int i = 0; i < extras.size(); i++) {
            extraMails.add(
                    mail.sendItemInTx(
                            row.owner(),
                            extras.get(i),
                            "trap_retrieve",
                            "trapbox:" + row.id() + ":" + i));
        }
        CompletableFuture<Void> done =
                chain.thenCompose(
                                v ->
                                        api.database()
                                                .async(
                                                        c -> {
                                                            Optional<TrapRow> now =
                                                                    TrapRepository.byId(
                                                                            c, row.id(), true);
                                                            // 그사이 새 수확물이 생겼으면 다음 회수 때 함께 보낸다
                                                            if (now.isEmpty()
                                                                    || now.get().bait()
                                                                            != row.bait()
                                                                    || !now.get()
                                                                            .contents()
                                                                            .isEmpty()) {
                                                                return false;
                                                            }
                                                            for (SqlConsumer m : extraMails) {
                                                                m.accept(c);
                                                            }
                                                            return TrapRepository.delete(
                                                                    c, row.id());
                                                        }))
                        .thenAccept(
                                deleted -> {
                                    if (Boolean.TRUE.equals(deleted) && !extraMails.isEmpty()) {
                                        mail.notifyArrived(row.owner());
                                    }
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
