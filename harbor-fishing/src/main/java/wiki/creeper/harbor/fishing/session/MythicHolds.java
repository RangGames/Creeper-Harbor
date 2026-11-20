package wiki.creeper.harbor.fishing.session;

import static wiki.creeper.harbor.fishing.session.FishingManager.COMPONENT;

import java.util.List;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;
import wiki.creeper.harbor.fishing.roll.RollContext;
import wiki.creeper.harbor.fishing.roll.RollEngine;
import wiki.creeper.harbor.fishing.sea.MythicCounter;

/** 신화 서버 마릿수 예약·반환과 보류 판정 정리 (DB 카운터는 MythicCounter). 메인 스레드 전용. */
final class MythicHolds {

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final MythicCounter mythicCounter;
    private final CastContext ctx;
    private final Supplier<RollEngine> engine;
    private final RandomGenerator random;

    MythicHolds(
            HarborApi api,
            Tasks tasks,
            Logger log,
            MythicCounter mythicCounter,
            CastContext ctx,
            Supplier<RollEngine> engine,
            RandomGenerator random) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.mythicCounter = mythicCounter;
        this.ctx = ctx;
        this.engine = engine;
        this.random = random;
    }

    /** 신화 서버 마릿수를 예약한다. 한도를 넘었으면 일반 판정으로 바꾼다. */
    FishingSession.MythicHold reserveMythic(
            Player player,
            FishingSession s,
            RollContext.MythicWindow window,
            RegionKeyResolver.RegionKey key,
            @Nullable ItemId bait) {
        FishingSession.MythicHold hold =
                new FishingSession.MythicHold(window.eventId(), window.fish().id().toString());
        var _ =
                mythicCounter
                        .reserve(hold.eventId, hold.fishId, window.cap())
                        .whenCompleteAsync(
                                (ok, error) -> {
                                    boolean reserved = error == null && Boolean.TRUE.equals(ok);
                                    if (error != null) {
                                        log.warn("신화 예약 실패: {}", String.valueOf(error));
                                    }
                                    if (reserved && hold.released) {
                                        // 기다리는 사이 캐스팅이 끝났으면 바로 돌려준다
                                        var _ = mythicCounter.release(hold.eventId, hold.fishId);
                                        return;
                                    }
                                    hold.confirmed = reserved;
                                    if (!reserved && s.mythic == hold && player.isOnline()) {
                                        hold.released = true;
                                        s.mythic = null;
                                        rerollWithoutMythic(player, s, key, bait);
                                    }
                                },
                                tasks.main());
        return hold;
    }

    void rerollWithoutMythic(
            Player player,
            FishingSession s,
            RegionKeyResolver.RegionKey key,
            @Nullable ItemId bait) {
        RollContext base = ctx.context(player, key, bait);
        RollContext rollCtx =
                new RollContext(
                        base.region(),
                        base.time(),
                        base.weather(),
                        base.moon(),
                        base.bait(),
                        base.jobTier(),
                        base.serverFlags(),
                        base.caught(),
                        base.areas(),
                        base.areasKnown(),
                        List.of());
        engine.get()
                .roll(
                        rollCtx,
                        ctx.modifiers(player.getUniqueId(), ctx.gearStats(player), key.region()),
                        api.players().get(player).get(COMPONENT).pity(),
                        random)
                .ifPresent(
                        o -> {
                            s.fish = o.fish();
                            s.legends = o.legendCandidates();
                        });
    }

    void releaseMythic(FishingSession.MythicHold hold) {
        if (hold.released) {
            return;
        }
        hold.released = true;
        if (hold.confirmed) {
            var _ = mythicCounter.release(hold.eventId, hold.fishId);
        }
    }

    void discardPending(FishingSession s) {
        FishingSession.PendingRoll pending = s.pending;
        if (pending != null) {
            FishingSession.MythicHold hold = pending.mythic();
            if (hold != null) {
                releaseMythic(hold);
            }
        }
        s.pending = null;
    }
}
