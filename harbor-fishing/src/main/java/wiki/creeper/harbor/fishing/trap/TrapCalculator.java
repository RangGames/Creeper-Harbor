package wiki.creeper.harbor.fishing.trap;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.session.FishingManager;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * 통발 오프라인 계산 (docs 20 3.4): 열 때 한 번, 최대 주기 수·계정 수익 상한 안에서 어획을 굴려 조건부 갱신으로 저장한다. 입력은 메인 스레드에서 고정하고
 * 판정은 가상 스레드에서 한다.
 */
final class TrapCalculator {

    private final HarborApi api;
    private final Tasks tasks;
    private final JobsService jobs;
    private final Logger log;
    private final FishingManager fishing;
    private final TrapManager traps;

    TrapCalculator(
            HarborApi api,
            Tasks tasks,
            JobsService jobs,
            Logger log,
            FishingManager fishing,
            TrapManager traps) {
        this.api = api;
        this.tasks = tasks;
        this.jobs = jobs;
        this.log = log;
        this.fishing = fishing;
        this.traps = traps;
    }

    /** 열 때 한 번 오프라인 계산 (docs 20 3.4). 주인이 접속해 있어야 한다 (직업 상한·스탯). */
    CompletableFuture<TrapManager.Calc> calculate(Player owner, long id) {
        UUID uuid = owner.getUniqueId();
        HarborPlayer hp = api.players().get(owner);
        ContentSnapshot snapshot = api.content().snapshot();
        FishingBalance b = traps.balance();
        Quality cap = jobs.qualityCap(uuid);
        boolean produce = hp.jobLine().map(l -> l == JobLine.PRODUCE).orElse(false);
        double ratio = b.trapRevenueRatio(produce, hp.jobTier().level());
        double bonusSlots = traps.stat(uuid, "trap_slots");
        double cycleDelta = traps.stat(uuid, "trap_cycle_hours");
        double extraChance = traps.stat(uuid, "trap_extra_chance");
        Predicate<String> flags = flagSnapshot(snapshot);
        int season = traps.season();
        SplittableRandom random = new SplittableRandom();
        // 지역 어종 풀과 통발 스펙은 메인 스레드에서 미리 만든다 (판정은 가상 스레드)
        Map<String, TrapMath.Spec> specs = new HashMap<>();
        for (ItemDef def : snapshot.all(ItemDef.TYPE).values()) {
            if (def.type() == ItemDef.Type.TRAP) {
                specs.put(def.id().toString(), TrapMath.Spec.of(def.stats()));
            }
        }
        var engine = fishing.engine();
        Duration window = Duration.ofMillis(Math.round(b.trapRevenueWindowHours() * 3_600_000));
        CompletableFuture<TrapManager.Calc> future =
                api.database()
                        .async(
                                c -> {
                                    List<TrapRow> rows =
                                            TrapRepository.byOwner(c, uuid, season, true);
                                    TrapRow row =
                                            rows.stream()
                                                    .filter(r -> r.id() == id)
                                                    .findFirst()
                                                    .orElse(null);
                                    if (row == null) {
                                        throw new IllegalStateException("통발이 없습니다: " + id);
                                    }
                                    Instant now = Instant.now();
                                    TrapMath.Spec spec =
                                            specs.getOrDefault(
                                                    row.item().toString(),
                                                    new TrapMath.Spec(
                                                            row.slots(),
                                                            Grade.COMMON,
                                                            1,
                                                            row.tier(),
                                                            false));
                                    Duration cycle =
                                            TrapMath.cycle(
                                                    b.trapCycleHours(),
                                                    spec.cycleMult(),
                                                    cycleDelta,
                                                    b.trapMinCycleHours());
                                    TrapMath.Accrual acc =
                                            TrapMath.accrue(
                                                    row.lastCalc(), now, cycle, b.trapMaxCycles());
                                    if (acc.cycles() == 0) {
                                        return new TrapManager.Calc(row, 0, false);
                                    }
                                    double hourly = 0;
                                    double others = 0;
                                    for (TrapRow r : rows) {
                                        hourly =
                                                Math.max(
                                                        hourly,
                                                        b.hourlyGold(r.region().id().name()));
                                        if (r.id() != id) {
                                            others +=
                                                    TrapMath.windowValue(
                                                            r.value24h(),
                                                            r.windowStart(),
                                                            now,
                                                            window);
                                        }
                                    }
                                    double own =
                                            TrapMath.windowValue(
                                                    row.value24h(), row.windowStart(), now, window);
                                    Instant windowStart =
                                            now.isBefore(row.windowStart().plus(window))
                                                    ? row.windowStart()
                                                    : now;
                                    TrapRoller roller =
                                            new TrapRoller(
                                                    engine.regionPool(row.region()),
                                                    spec.maxGrade(),
                                                    flags,
                                                    b,
                                                    cap,
                                                    random);
                                    int slots =
                                            roller.isEmpty()
                                                    ? 0
                                                    : TrapMath.effectiveSlots(
                                                            row.slots(),
                                                            row.extraSlots(),
                                                            bonusSlots);
                                    TrapMath.Production p =
                                            TrapMath.produce(
                                                    acc.cycles(),
                                                    slots,
                                                    row.bait(),
                                                    b.trapBaitPerSlot(),
                                                    roller.isEmpty() ? 0 : extraChance,
                                                    others + own,
                                                    TrapMath.accountCap(hourly, ratio),
                                                    roller,
                                                    now,
                                                    random);
                                    List<TrapCatch> contents = new ArrayList<>(row.contents());
                                    contents.addAll(p.catches());
                                    int bait = row.bait() - p.baitUsed();
                                    double value = own + p.value();
                                    boolean saved =
                                            TrapRepository.saveCalc(
                                                    c,
                                                    id,
                                                    row.lastCalc(),
                                                    acc.lastCalc(),
                                                    contents,
                                                    bait,
                                                    value,
                                                    windowStart);
                                    if (!saved) {
                                        // 다른 곳에서 먼저 계산했다: 그 결과를 그대로 쓴다
                                        return new TrapManager.Calc(
                                                TrapRepository.byId(c, id, false).orElse(row),
                                                0,
                                                false);
                                    }
                                    return new TrapManager.Calc(
                                            new TrapRow(
                                                    row.id(),
                                                    row.owner(),
                                                    row.season(),
                                                    row.region(),
                                                    row.world(),
                                                    row.x(),
                                                    row.y(),
                                                    row.z(),
                                                    row.tier(),
                                                    row.item(),
                                                    row.slots(),
                                                    row.extraSlots(),
                                                    bait,
                                                    acc.lastCalc(),
                                                    contents,
                                                    value,
                                                    windowStart,
                                                    row.lastHarvest(),
                                                    row.placedAt()),
                                            p.catches().size(),
                                            p.capReached());
                                })
                        .thenApplyAsync(calc -> calc, tasks.main());
        var _ =
                future.exceptionally(
                        e -> {
                            log.warn("통발 계산 실패 ({}): {}", id, String.valueOf(e));
                            var _ =
                                    tasks.sync(
                                            () ->
                                                    api.messages()
                                                            .send(owner, "fishing.trap.failed"));
                            return null;
                        });
        return traps.track(uuid, future);
    }

    /** 서버 플래그를 지금 값으로 고정한다 (가상 스레드에서 FlagService를 부르지 않게). */
    private Predicate<String> flagSnapshot(ContentSnapshot snapshot) {
        Set<String> on = new HashSet<>();
        for (FishDef f : snapshot.all(FishDef.TYPE).values()) {
            String flag = f.unlockFlag();
            if (flag != null && api.flags().hasServer(flag)) {
                on.add(flag);
            }
        }
        return on::contains;
    }
}
