package wiki.creeper.harbor.boss.encounter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.data.BossRepository;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.SeasonServices;

/**
 * boss_encounter and participation records (BossRepository): start/end records, restart recovery,
 * ABORTED records on server shutdown. The DB is only written asynchronously (except during
 * shutdown).
 */
final class EncounterRecorder {

    private final EncounterManager mgr;

    EncounterRecorder(EncounterManager mgr) {
        this.mgr = mgr;
    }

    /**
     * Writes the boss_encounter RUNNING row asynchronously. The result future completes on the main
     * thread.
     */
    CompletableFuture<Void> recordStart(Encounter e) {
        SeasonClock clock = SeasonServices.clock();
        BossRepository.EncounterRow row =
                new BossRepository.EncounterRow(
                        e.id,
                        e.def.id(),
                        clock.current().value(),
                        clock.week(),
                        mgr.serverId(),
                        e.slot,
                        Math.max(1, e.members.size()),
                        e.startedAt(),
                        e.def.isWorld() ? null : e.leader,
                        e.summonItem == null ? null : e.summonItem.toString(),
                        e.seed);
        CompletableFuture<Void> done = new CompletableFuture<>();
        var _ =
                mgr.api()
                        .database()
                        .asyncVoid(c -> BossRepository.insertEncounter(c, row))
                        .whenCompleteAsync(
                                (v, err) -> {
                                    if (err != null) {
                                        done.completeExceptionally(err);
                                    } else {
                                        done.complete(null);
                                    }
                                },
                                mgr.tasks().main());
        return done;
    }

    /**
     * Writes the end result and participation records asynchronously (failures are only logged).
     */
    void recordEnd(
            Encounter e,
            EncounterState result,
            Instant endedAt,
            long durationMs,
            List<Contribution> contributions) {
        int season = SeasonServices.clock().current().value();
        int size = contributions.size();
        var _ =
                mgr.api()
                        .database()
                        .asyncVoid(
                                c -> {
                                    BossRepository.endEncounter(
                                            c,
                                            e.id,
                                            result.name(),
                                            endedAt,
                                            durationMs,
                                            Math.max(1, size));
                                    BossRepository.upsertParticipation(
                                            c, e.id, season, contributions);
                                })
                        .exceptionally(
                                err -> {
                                    mgr.log()
                                            .warn(
                                                    "조우 종료 기록 실패 {}: {}",
                                                    e.id,
                                                    String.valueOf(err.getMessage()));
                                    return null;
                                });
    }

    /**
     * On restart, closes RUNNING encounters as ABORTED and refunds bait via pending (10.4 abnormal
     * server shutdown).
     */
    void recover() {
        String server = mgr.serverId();
        var _ =
                mgr.api()
                        .database()
                        .async(
                                c -> {
                                    // Close and refund in one transaction: if it fails in
                                    // between, both roll back and the next restart retries
                                    List<BossRepository.Aborted> aborted =
                                            BossRepository.abortRunning(c, server);
                                    List<UUID> refunded = new ArrayList<>();
                                    for (BossRepository.Aborted a : aborted) {
                                        UUID leader = mgr.rewards().refundBaitInTx(c, a);
                                        if (leader != null) {
                                            refunded.add(leader);
                                        }
                                    }
                                    return new Recovered(aborted.size(), refunded);
                                })
                        .thenAcceptAsync(
                                r -> {
                                    if (r.aborted() > 0) {
                                        mgr.log()
                                                .warn(
                                                        "비정상 종료로 남은 보스 조우 {}개를 ABORTED로 닫았습니다",
                                                        r.aborted());
                                    }
                                    for (UUID leader : r.refunded()) {
                                        mgr.rewards().notifyRefund(leader);
                                    }
                                },
                                mgr.tasks().main())
                        .exceptionally(
                                err -> {
                                    mgr.log()
                                            .warn(
                                                    "보스 조우 복구 실패: {}",
                                                    String.valueOf(err.getMessage()));
                                    return null;
                                });
    }

    private record Recovered(int aborted, List<UUID> refunded) {}

    /**
     * ABORTED records during server shutdown (bait refunded via pending). Waits up to 5 seconds
     * since the server is shutting down.
     */
    void abortOnShutdown(Encounter e) {
        try {
            var _ =
                    mgr.api()
                            .database()
                            .asyncVoid(
                                    c -> {
                                        BossRepository.endEncounter(
                                                c,
                                                e.id,
                                                "ABORTED",
                                                Instant.now(),
                                                e.fight.elapsed() * 50L,
                                                Math.max(1, e.members.size()));
                                        // Refund the bait if it was taken, even during departure
                                        // (before recording)
                                        ItemId item = e.summonItem;
                                        if (e.baitTaken && item != null) {
                                            mgr.rewards().refundInTx(c, e.id, e.leader, item);
                                        }
                                    })
                            .get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException
                | java.util.concurrent.TimeoutException ex) {
            mgr.log().warn("종료 중 조우 기록 실패 {}: {}", e.id, String.valueOf(ex.getMessage()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
