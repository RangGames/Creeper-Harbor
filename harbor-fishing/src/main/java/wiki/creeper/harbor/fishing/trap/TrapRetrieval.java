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
 * Trap auto-retrieval: once a day one main server takes a distributed lock, sends the contents of
 * traps not harvested for 7 days via Core MailService, then removes the rows and blocks. Deferred
 * if MailService is missing. Starts on the main thread; DB and mail are async.
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

    /** Once a day (one main server): retrieves traps not harvested for 7 days by mail. */
    private void autoRetrieve() {
        if (api.infra().role() != wiki.creeper.harbor.common.config.ServerRole.MAIN) {
            return;
        }
        Optional<MailService> mail = api.mail();
        if (mail.isEmpty()) {
            // Defer if there is no mailbox (Economy) (docs 20 3.8)
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

    /**
     * One at a time: each entry is removed from contents after its mail commits (if interrupted,
     * duplicate delivery is at most 1).
     */
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
            // Mail and entry deletion in one transaction, only if the entry is still in the trap
            // (skipped if the owner harvested it after the lookup: previously the list from the
            // lookup was sent, so it was received twice)
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
        // The trap item and bait refund mail and the row deletion happen in one transaction
        // (nothing is lost or sent twice even if the server dies midway). Mail reference IDs must
        // differ per entry (if equal, the mailbox unique key discards all but the first). If the
        // owner added bait or removed the trap in the meantime, skip it this time
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
                                                            // If new catches appeared in the
                                                            // meantime, send them with the next
                                                            // retrieval
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
