package wiki.creeper.harbor.fishing.session;

import static wiki.creeper.harbor.fishing.session.FishingManager.COMPONENT;
import static wiki.creeper.harbor.fishing.session.FishingManager.loc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerFishEvent;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.fishing.api.RegionKeyResolver;
import wiki.creeper.harbor.fishing.api.SessionState;
import wiki.creeper.harbor.fishing.api.ShadowDriver;
import wiki.creeper.harbor.fishing.api.event.FishBiteEvent;
import wiki.creeper.harbor.fishing.api.event.FishRollEvent;
import wiki.creeper.harbor.fishing.api.event.FishingCastEvent;
import wiki.creeper.harbor.fishing.api.event.FishingSessionEndEvent;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.data.FishingData;
import wiki.creeper.harbor.fishing.present.CatchPresenter;
import wiki.creeper.harbor.fishing.present.ShadowPresenter;
import wiki.creeper.harbor.fishing.roll.BaitRule;
import wiki.creeper.harbor.fishing.roll.RollContext;
import wiki.creeper.harbor.fishing.roll.RollEngine;

/**
 * From cast to hook (docs 20 3.4 CAST → WAITING → BITE → HOOKED → MINIGAME). Handles the throw, the
 * bobber landing roll, the bite, hooking (bait deduction, minigame start) and holding the roll of
 * casts that ended before the minigame. Main thread only.
 */
final class CastHandler {

    private static final Duration PENDING_TTL = Duration.ofSeconds(60);
    private static final int MAX_CAST_TICKS = 20 * 20;

    private final HarborApi api;
    private final MacroGuard macro;
    private final CastContext ctx;
    private final FishingGear gear;
    private final MythicHolds mythic;
    private final MinigameDisplay display;
    private final CatchPresenter presenter;
    private final ShadowPresenter shadows;
    private final Supplier<RollEngine> engine;
    private final RandomGenerator random;
    private final Map<UUID, TensionSessionImpl> tensions;

    CastHandler(
            HarborApi api,
            MacroGuard macro,
            CastContext ctx,
            FishingGear gear,
            MythicHolds mythic,
            MinigameDisplay display,
            CatchPresenter presenter,
            ShadowPresenter shadows,
            Supplier<RollEngine> engine,
            RandomGenerator random,
            Map<UUID, TensionSessionImpl> tensions) {
        this.api = api;
        this.macro = macro;
        this.ctx = ctx;
        this.gear = gear;
        this.mythic = mythic;
        this.display = display;
        this.presenter = presenter;
        this.shadows = shadows;
        this.engine = engine;
        this.random = random;
        this.tensions = tensions;
    }

    /** Throw: decides the wait time and waits for the bobber to hit water (rolled on landing). */
    void cast(PlayerFishEvent event, Player player, FishingSession s) {
        UUID uuid = player.getUniqueId();
        if (s.state == SessionState.MINIGAME
                || s.state == SessionState.RESULT
                || tensions.containsKey(uuid)) {
            event.setCancelled(true);
            return;
        }
        if (macro.blocked(player)) {
            event.setCancelled(true);
            api.messages().send(player, "fishing.macro.blocked");
            return;
        }
        if (FishingGear.rodBroken(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            api.messages().send(player, "fishing.rod_broken");
            return;
        }
        if (ctx.regionAt(loc(player)).isEmpty() && !ctx.areasKnown()) {
            event.setCancelled(true);
            api.ui().actionBar(player, api.messages().get("fishing.no_region"));
            return;
        }
        ItemId bait = ctx.equippedBait(player);
        Map<String, Double> gear = ctx.gearStats(player);
        FishingBalance b = ctx.balance();
        double seconds =
                b.biteWaitMin() + random.nextDouble() * (b.biteWaitMax() - b.biteWaitMin());
        seconds *= ctx.stat(uuid, "bite_wait") * (1 + gear.getOrDefault("bite_wait", 0.0));
        if (bait == null) {
            seconds *= b.noBaitWaitMult();
        }
        int waitTicks = (int) Math.max(20, Math.round(seconds * 20));
        FishHook hook = event.getHook();
        hook.setApplyLure(false);
        hook.setWaitTime(waitTicks, waitTicks);
        double distance = ctx.stat(uuid, "cast_distance") + gear.getOrDefault("cast_distance", 0.0);
        if (distance > 0 && Math.abs(distance - 1) > 1e-6) {
            hook.setVelocity(hook.getVelocity().multiply(distance));
        }
        if (s.state != SessionState.IDLE) {
            // The previous cast ended without an event (vanilla removed the bobber on hotbar slot
            // change, moving far away or teleport). Only calling clearCast would not return a
            // confirmed mythic reservation and the shadow, permanently shrinking the server limit:
            // handle it as a normal early end (the roll stays pending)
            endEarly(player, s, FishingSessionEndEvent.Reason.INTERRUPTED);
        }
        s.clearCast();
        s.state = SessionState.CAST;
        s.hook = hook;
        s.bait = bait;
        s.waitTicks = waitTicks;
        macro.recordCast(player);
        presenter.step(loc(player), "cast");
    }

    void tickCast(Player player, FishingSession s) {
        FishHook hook = s.hook;
        s.castTicks++;
        if (hook == null || !hook.isValid() || s.castTicks > MAX_CAST_TICKS) {
            s.clearCast();
            return;
        }
        if (hook.getState() == FishHook.HookState.BOBBING || hook.isInWater()) {
            land(player, s, hook);
        }
    }

    /** Bobber landing: spawn roll and waiting presentation (docs 20 3.4 CAST → WAITING). */
    private void land(Player player, FishingSession s, FishHook hook) {
        Location at = loc(hook);
        presenter.step(at, MinigameDisplay.landStep(at));
        Optional<RegionKeyResolver.RegionKey> key = ctx.regionKey(at);
        if (key.isEmpty()) {
            hook.remove();
            s.clearCast();
            api.ui().actionBar(player, api.messages().get("fishing.no_region"));
            return;
        }
        ItemId bait = ctx.equippedBait(player);
        s.bait = bait;
        RegionId region = key.get().region();
        UUID uuid = player.getUniqueId();
        FishDef fish;
        List<FishDef> legends;
        RollContext.MythicWindow mythicWindow = null;
        FishingSession.MythicHold hold = null;
        String pendingKey = FishingSession.pendingKey(region, bait);
        FishingSession.PendingRoll pending = s.pendings.remove(pendingKey);
        FishDef forced = s.forcedFish;
        if (forced != null) {
            fish = forced;
            legends = List.of();
            s.forcedFish = null;
            if (pending != null) {
                mythic.releasePending(pending);
            }
        } else if (pending != null && Instant.now().isBefore(pending.expiresAt())) {
            // Prevent reroll fishing: for the same region and bait, reuse the pending roll as is
            fish = pending.fish();
            legends = pending.legends();
            hold = pending.mythic();
        } else {
            if (pending != null) {
                mythic.releasePending(pending);
            }
            Optional<RollEngine.Outcome> outcome =
                    engine.get()
                            .roll(
                                    ctx.context(player, key.get(), bait),
                                    ctx.modifiers(uuid, ctx.gearStats(player), region),
                                    api.players().get(player).get(COMPONENT).pity(),
                                    random);
            if (outcome.isEmpty()) {
                hook.remove();
                s.clearCast();
                api.ui().actionBar(player, api.messages().get("fishing.no_fish"));
                return;
            }
            fish = outcome.get().fish();
            legends = outcome.get().legendCandidates();
            mythicWindow = outcome.get().mythic();
        }
        FishRollEvent roll = new FishRollEvent(player, fish, bait);
        Bukkit.getPluginManager().callEvent(roll);
        if (roll.isCancelled()) {
            hook.remove();
            if (hold != null) {
                mythic.releaseMythic(hold);
            }
            s.clearCast();
            return;
        }
        if (mythicWindow != null && roll.fish().id().equals(mythicWindow.fish().id())) {
            hold = mythic.reserveMythic(player, s, mythicWindow, key.get(), bait);
        }
        int waitTicks = s.waitTicks;
        s.fish = roll.fish();
        s.region = region;
        s.legends = legends;
        s.school = key.get().school();
        s.hookLoc = at;
        s.mythic = hold;
        s.state = SessionState.WAITING;
        s.castTicks = 0;
        s.settings = FishingSettings.of(api.flags(), uuid);

        FishingCastEvent castEvent =
                new FishingCastEvent(
                        player, at, region, bait, roll.fish(), waitTicks, key.get().school());
        Bukkit.getPluginManager().callEvent(castEvent);
        ShadowDriver driver = castEvent.shadow();
        if (driver != null || presenter.shadows(s.settings.intensity())) {
            s.shadow =
                    new ShadowControl(
                            shadows,
                            player,
                            roll.fish(),
                            ctx.presets().of(roll.fish().grade()),
                            ctx.stat(uuid, "feature_shadow_grade") > 0,
                            at,
                            waitTicks,
                            driver,
                            random);
        }
    }

    void bite(Player player, FishingSession s) {
        FishDef fish = s.fish;
        if (s.state != SessionState.WAITING || fish == null) {
            return;
        }
        s.state = SessionState.BITE;
        s.biteAtNanos = System.nanoTime();
        // Legendary pity: +1 only for casts that reached a bite (prevents stacking by reeling right
        // after casting)
        if (!s.pityCounted && !s.legends.isEmpty()) {
            HarborPlayer hp = api.players().get(player);
            FishingData data = hp.get(COMPONENT);
            for (FishDef legend : s.legends) {
                data = data.withPity(legend.id(), data.pity(legend.id()) + 1);
            }
            hp.set(COMPONENT, data);
            s.pityCounted = true;
        }
        Location hook = s.hookLoc;
        if (hook != null) {
            presenter.bite(player, fish, hook, s.settings.intensity());
        }
        ShadowControl shadow = s.shadow;
        if (shadow != null) {
            shadow.bite();
        }
        api.ui().actionBar(player, api.messages().get("fishing.bite"));
        Bukkit.getPluginManager().callEvent(new FishBiteEvent(player, fish));
    }

    /** Hook: blocks the vanilla loot, uses the bait and starts the minigame. */
    void hooked(PlayerFishEvent event, Player player, FishingSession s) {
        event.setCancelled(true);
        event.getHook().remove();
        FishDef fish = s.fish;
        if (fish == null || (s.state != SessionState.BITE && s.state != SessionState.WAITING)) {
            closeShadow(s, false);
            s.clearCast();
            return;
        }
        // Discard the roll if the bait was changed or dropped while waiting
        if (!Objects.equals(ctx.equippedBait(player), s.bait)) {
            endEarly(player, s, FishingSessionEndEvent.Reason.BAIT_CHANGED);
            mythic.discardPending(s);
            api.messages().send(player, "fishing.bait_changed");
            return;
        }
        FishingSession.MythicHold hold = s.mythic;
        if (hold != null && !hold.confirmed) {
            // If the reservation confirmation is late, use a normal roll instead of the mythic
            // (duplication prevention first)
            mythic.releaseMythic(hold);
            s.mythic = null;
            Location hookLoc = s.hookLoc;
            if (hookLoc != null) {
                ctx.regionKey(hookLoc)
                        .ifPresent(k -> mythic.rerollWithoutMythic(player, s, k, s.bait));
            }
            fish = Objects.requireNonNull(s.fish);
        }
        if (s.biteAtNanos > 0) {
            macro.recordReaction(player, System.nanoTime() - s.biteAtNanos);
        }
        s.state = SessionState.HOOKED;
        s.legendBait = gear.isLegendBait(s.bait);
        if (BaitRule.consumeOnHook(s.legendBait)) {
            gear.consumeBait(player, s.bait);
        }

        FishingBalance b = ctx.balance();
        Location hookLoc = s.hookLoc;
        boolean longCast =
                hookLoc != null
                        && player.getWorld().equals(hookLoc.getWorld())
                        && hookLoc.distance(loc(player)) >= b.longCastDistance();
        s.minigame =
                new Minigame(
                        b.minigame(fish.grade()),
                        ctx.gear(player, 0, longCast),
                        b.lineStrengthStep(),
                        random);
        s.state = SessionState.MINIGAME;
        presenter.step(hookLoc != null ? hookLoc : loc(player), "hook");
        s.inputSneak = false;
        s.baseYaw = loc(player).getYaw();
        s.lastPhase = 1;
        display.lockMovement(player, true);
        s.bar =
                api.ui()
                        .showBossBar(
                                player,
                                api.messages().get("fishing.minigame.unknown"),
                                1f,
                                MinigameDisplay.barColor(fish.grade()));
        api.messages().send(player, "fishing.minigame.help");
    }

    /**
     * A cast that ended before the minigame (early reel, missed bite, stuck in ground). The roll is
     * held for 60 seconds.
     */
    void endEarly(Player player, FishingSession s, FishingSessionEndEvent.Reason reason) {
        FishDef fish = s.fish;
        RegionId region = s.region;
        if (s.state == SessionState.MINIGAME || s.state == SessionState.RESULT) {
            return;
        }
        closeShadow(s, false);
        if (fish != null
                && region != null
                && reason != FishingSessionEndEvent.Reason.BAIT_CHANGED) {
            FishingSession.PendingRoll previous =
                    s.pendings.put(
                            FishingSession.pendingKey(region, s.bait),
                            new FishingSession.PendingRoll(
                                    region,
                                    s.bait,
                                    fish,
                                    s.legends,
                                    s.mythic,
                                    Instant.now().plus(PENDING_TTL)));
            if (previous != null) {
                mythic.releasePending(previous);
            }
            s.mythic = null;
        } else {
            FishingSession.MythicHold hold = s.mythic;
            if (hold != null) {
                mythic.releaseMythic(hold);
            }
        }
        s.clearCast();
        if (fish != null) {
            Bukkit.getPluginManager().callEvent(new FishingSessionEndEvent(player, fish, reason));
        }
    }

    static void closeShadow(FishingSession s, boolean caught) {
        ShadowControl shadow = s.shadow;
        if (shadow != null) {
            shadow.close(caught);
            s.shadow = null;
        }
    }
}
