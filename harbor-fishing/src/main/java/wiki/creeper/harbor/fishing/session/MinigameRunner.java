package wiki.creeper.harbor.fishing.session;

import static wiki.creeper.harbor.fishing.session.FishingManager.loc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.WorldBorderService;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.SessionState;
import wiki.creeper.harbor.fishing.api.TensionSession;
import wiki.creeper.harbor.fishing.api.TensionSpec;
import wiki.creeper.harbor.fishing.api.event.FishingSessionEndEvent;
import wiki.creeper.harbor.fishing.api.event.MinigameEndEvent;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.present.CatchPresenter;
import wiki.creeper.harbor.fishing.roll.BaitRule;

/**
 * Minigame progress (docs 20 3.4 MINIGAME → RESULT). Advances fishing minigames and Boss tension
 * sessions every tick; when finished, handles bait deduction/return and failure presentation, and
 * on success hands off to CatchRewards. Main thread only.
 */
final class MinigameRunner {

    private final HarborApi api;
    private final CastContext ctx;
    private final FishingGear gear;
    private final MythicHolds mythic;
    private final MinigameDisplay display;
    private final CastHandler casts;
    private final CatchRewards rewards;
    private final CatchPresenter presenter;
    private final MinigameStats stats;
    private final RandomGenerator random;
    private final Map<UUID, FishingSession> sessions;
    private final Map<UUID, TensionSessionImpl> tensions;

    MinigameRunner(
            HarborApi api,
            CastContext ctx,
            FishingGear gear,
            MythicHolds mythic,
            MinigameDisplay display,
            CastHandler casts,
            CatchRewards rewards,
            CatchPresenter presenter,
            MinigameStats stats,
            RandomGenerator random,
            Map<UUID, FishingSession> sessions,
            Map<UUID, TensionSessionImpl> tensions) {
        this.api = api;
        this.ctx = ctx;
        this.gear = gear;
        this.mythic = mythic;
        this.display = display;
        this.casts = casts;
        this.rewards = rewards;
        this.presenter = presenter;
        this.stats = stats;
        this.random = random;
        this.sessions = sessions;
        this.tensions = tensions;
    }

    // ------------------------------------------------------------------ Tension sessions (Boss)

    /** FishingService.openTension. */
    Optional<TensionSession> openTension(Player player, TensionSpec spec) {
        UUID uuid = player.getUniqueId();
        if (tensions.containsKey(uuid)) {
            return Optional.empty();
        }
        FishingSession s = sessions.get(uuid);
        if (s != null && s.state == SessionState.MINIGAME) {
            abort(player, MinigameResult.ABORTED);
        } else if (s != null && s.state != SessionState.IDLE) {
            casts.endEarly(player, s, FishingSessionEndEvent.Reason.INTERRUPTED);
        }
        FishingBalance b = ctx.balance();
        FishingBalance.MinigameSpec base = b.minigame(spec.grade());
        FishingBalance.MinigameSpec adjusted =
                new FishingBalance.MinigameSpec(
                        spec.hp() > 0 ? spec.hp() : base.hp(),
                        base.tensionRise() * spec.tensionRiseMult(),
                        base.releaseRate(),
                        base.reelPerTick(),
                        spec.patterns() ? base.patterns() : 0,
                        base.patternIntervalSeconds(),
                        base.safeLow(),
                        base.safeWidth(),
                        base.escapeTicks(),
                        base.phased());
        Minigame game =
                new Minigame(
                        adjusted,
                        ctx.gear(player, spec.extraLineStrength(), false),
                        b.lineStrengthStep(),
                        random);
        TensionSessionImpl session =
                new TensionSessionImpl(uuid, game, spec, () -> closeTension(player));
        session.baseYaw = loc(player).getYaw();
        session.bar =
                api.ui()
                        .showBossBar(
                                player, spec.title(), 1f, MinigameDisplay.barColor(spec.grade()));
        tensions.put(uuid, session);
        display.lockMovement(player, true);
        api.messages().send(player, "fishing.minigame.help");
        return Optional.of(session);
    }

    private void closeTension(Player player) {
        TensionSessionImpl t = tensions.remove(player.getUniqueId());
        if (t == null) {
            return;
        }
        BossBar bar = t.bar;
        if (bar != null) {
            api.ui().hideBossBar(player, bar);
        }
        if (t.borderWarn) {
            api.worldBorders().clear(player, WorldBorderService.Priority.FISHING_TENSION);
        }
        display.lockMovement(player, false);
    }

    // ------------------------------------------------------------------ Tick

    void tickMinigame(Player player, FishingSession s) {
        Minigame game = s.minigame;
        FishDef fish = s.fish;
        if (game == null || fish == null) {
            return;
        }
        Minigame.Pattern warningBefore = game.warning();
        Minigame.Input input =
                display.input(
                        player,
                        s.inputSneak,
                        s.inputLeft,
                        s.inputRight,
                        s.settings.lookMode(),
                        s.baseYaw);
        MinigameResult result = game.tick(input);
        Minigame.Pattern warning = game.warning();
        if (warning != null && warningBefore == null) {
            api.ui()
                    .title(
                            player,
                            Component.empty(),
                            api.messages()
                                    .get(
                                            "fishing.pattern."
                                                    + warning.name().toLowerCase(Locale.ROOT)),
                            Duration.ZERO,
                            Duration.ofMillis(700),
                            Duration.ofMillis(200));
        }
        ShadowControl shadow = s.shadow;
        if (shadow != null) {
            shadow.tickFight(game.direction(), game.ticks());
            if (game.active() == Minigame.Pattern.JUMP && s.lastActive != Minigame.Pattern.JUMP) {
                shadow.jump(
                        presenter.jumpViewers(s.settings.intensity())
                                ? display.nearby(player)
                                : List.of());
            }
        }
        s.lastActive = game.active();
        if (game.phase() > s.lastPhase) {
            s.lastPhase = game.phase();
            display.phaseSky(player, game.phase(), s.settings.intensity(), fish);
        }
        display.warnBorder(player, game.tension(), s.borderWarn, w -> s.borderWarn = w);
        s.reel = display.reelSound(player, s.inputSneak, s.reel);
        if (game.ticks() % 2 == 0) {
            api.ui().actionBar(player, MinigameDisplay.gauge(game, input));
            BossBar bar = s.bar;
            if (bar != null) {
                bar.progress((float) game.hpRatio());
            }
        }
        if (result != null) {
            end(player, s, result);
        }
    }

    void tickTension(TensionSessionImpl t) {
        Player player = Bukkit.getPlayer(t.player());
        if (player == null) {
            t.close(MinigameResult.ESCAPED);
            return;
        }
        if (!t.isActive()) {
            return;
        }
        Minigame game = t.game();
        Minigame.Input input =
                display.input(
                        player,
                        inputSneak(player),
                        inputLeft(player),
                        inputRight(player),
                        FishingSettings.of(api.flags(), player.getUniqueId()).lookMode(),
                        t.baseYaw);
        MinigameResult result = game.tick(input);
        display.warnBorder(player, game.tension(), t.borderWarn, w -> t.borderWarn = w);
        t.reel = display.reelSound(player, inputSneak(player), t.reel);
        if (game.ticks() % 2 == 0) {
            api.ui().actionBar(player, MinigameDisplay.gauge(game, input));
            BossBar bar = t.bar;
            if (bar != null) {
                bar.progress((float) game.hpRatio());
            }
        }
        if (result != null) {
            t.finish(result);
        }
    }

    private boolean inputSneak(Player player) {
        FishingSession s = sessions.get(player.getUniqueId());
        return s != null && s.inputSneak;
    }

    private boolean inputLeft(Player player) {
        FishingSession s = sessions.get(player.getUniqueId());
        return s != null && s.inputLeft;
    }

    private boolean inputRight(Player player) {
        FishingSession s = sessions.get(player.getUniqueId());
        return s != null && s.inputRight;
    }

    // ------------------------------------------------------------------ End · abort

    /** Handles the minigame end (success, failure, abort). */
    private void end(Player player, FishingSession s, MinigameResult result) {
        FishDef fish = s.fish;
        Minigame game = s.minigame;
        BossBar bar = s.bar;
        if (bar != null) {
            api.ui().hideBossBar(player, bar);
        }
        s.bar = null;
        s.reel.stop();
        s.reel = HarborSounds.Loop.NONE;
        display.lockMovement(player, false);
        if (s.borderWarn) {
            api.worldBorders().clear(player, WorldBorderService.Priority.FISHING_TENSION);
        }
        if (s.lastPhase > 1) {
            player.resetPlayerWeather();
            player.resetPlayerTime();
        }
        RegionId region = s.region;
        ItemId bait = s.bait;
        boolean legendBait = s.legendBait;
        Location hookLoc = s.hookLoc;
        FishingSession.MythicHold hold = s.mythic;
        CastHandler.closeShadow(s, result == MinigameResult.CAUGHT);
        s.state = SessionState.RESULT;
        s.clearCast();
        if (fish == null || game == null) {
            return;
        }
        stats.record(fish.grade(), result, game.ticks(), Instant.now());
        Bukkit.getPluginManager()
                .callEvent(new MinigameEndEvent(player, fish, result, game.ticks()));
        if (hold != null && result != MinigameResult.CAUGHT) {
            // Return the mythic reservation
            mythic.releaseMythic(hold);
        }
        if (bait != null && player.isOnline()) {
            if (BaitRule.consumeOnResult(legendBait, result, fish.grade())) {
                gear.consumeBait(player, bait);
            }
            if (BaitRule.refund(
                    legendBait,
                    result,
                    random.nextDouble(),
                    ctx.stat(player.getUniqueId(), "bait_save_chance"))) {
                gear.refundBait(player, bait);
            }
        }
        switch (result) {
            case CAUGHT -> {
                if (region != null) {
                    rewards.catchFish(player, s, fish, region, hookLoc);
                }
            }
            case LINE_BROKE -> {
                gear.damageRod(player);
                presenter.step(hookLoc != null ? hookLoc : loc(player), "line_snap");
                api.messages().send(player, "fishing.result.line_broke");
            }
            case ESCAPED -> {
                gear.damageRod(player);
                presenter.step(
                        hookLoc != null ? hookLoc : loc(player),
                        fish.grade().isAtLeast(Grade.RARE) ? "escape_big" : "escape");
                api.messages().send(player, "fishing.result.escaped");
            }
            case ABORTED -> {
                // Forced move (maintenance) etc.: normal bait was returned above
            }
        }
    }

    /** Ends it as a failure if in a minigame (bait already deducted, pity kept). */
    void abort(Player player, MinigameResult reason) {
        FishingSession s = sessions.get(player.getUniqueId());
        Minigame game = s == null ? null : s.minigame;
        if (s != null && game != null && s.state == SessionState.MINIGAME) {
            game.abort(reason);
            end(player, s, reason);
        }
    }
}
