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
 * boss_encounter·참가 기록 (BossRepository): 시작·종료 기록, 재시작 복구, 서버 종료 때 ABORTED 기록. DB는 비동기로만 쓴다 (종료 중
 * 제외).
 */
final class EncounterRecorder {

    private final EncounterManager mgr;

    EncounterRecorder(EncounterManager mgr) {
        this.mgr = mgr;
    }

    /** boss_encounter RUNNING 행을 비동기로 쓴다. 결과 future는 메인 스레드에서 완료된다. */
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

    /** 종료 결과와 참가 기록을 비동기로 쓴다 (실패는 로그만). */
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

    /** 재시작 때 RUNNING 조우를 ABORTED로 닫고 미끼를 pending으로 돌려준다 (10.4 서버 비정상 종료). */
    void recover() {
        String server = mgr.serverId();
        var _ =
                mgr.api()
                        .database()
                        .async(
                                c -> {
                                    // 닫기와 환급을 한 트랜잭션으로: 사이에서 실패하면 둘 다 되돌아가 다음 재시작에 다시 한다
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

    /** 서버 종료 중 ABORTED 기록 (미끼는 pending으로 돌려준다). 종료 중이라 최대 5초 기다린다. */
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
                                        // 출발 중(기록 전)이라도 미끼를 뺐으면 돌려준다
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
