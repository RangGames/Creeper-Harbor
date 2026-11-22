package wiki.creeper.harbor.npc.wanderer;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.api.event.ServerFlagChangedEvent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.npc.Worlds;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.WandererService;
import wiki.creeper.harbor.npc.api.WandererState;
import wiki.creeper.harbor.npc.api.event.WandererMovedEvent;
import wiki.creeper.harbor.npc.content.Conditions;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.content.Spot;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.logic.WandererSchedule;

/**
 * Wanderer NPC schedule (docs 20 5.4, Phase 3). When the slot changes, picks a candidate seeded by
 * hash(npc, season, slot); conditional candidates (time, weather) are checked every minute and
 * placed only when they match. If a player is in dialog, the move is postponed up to 60 seconds.
 * Admin forced moves use server flag wanderer/<name>.
 */
public final class WandererManager implements WandererService, NpcManager.Placement, Listener {

    private static final class State {
        long slot;
        @Nullable String candidate;
        boolean visible;
        boolean forced;
        @Nullable Instant pendingSince;
    }

    private final HarborApi api;
    private final Tasks tasks;
    private final Worlds worlds;
    private final NpcManager npcs;
    private final DialogService dialogs;
    private final Logger log;
    private final boolean announce;
    private final Map<NpcId, State> states = new HashMap<>();
    private int ticks;

    public WandererManager(
            HarborApi api,
            Tasks tasks,
            Worlds worlds,
            NpcManager npcs,
            DialogService dialogs,
            Logger log,
            boolean announce) {
        this.api = api;
        this.tasks = tasks;
        this.worlds = worlds;
        this.npcs = npcs;
        this.dialogs = dialogs;
        this.log = log;
        this.announce = announce;
    }

    /** Checks for slot changes every 5 seconds and spawn conditions every check_interval_ticks. */
    public void start() {
        var _ = tasks.timer(100, 100, this::tick);
    }

    private NpcBalance balance() {
        return new NpcBalance(api.content().snapshot().single(Balance.TYPE));
    }

    private ZoneId zone() {
        return api.time().zone();
    }

    private int slotHours(NpcDef def, NpcBalance balance) {
        NpcDef.Schedule s = def.schedule();
        int hours = s == null || s.slotHours() == 0 ? balance.wandererSlotHours() : s.slotHours();
        return Math.max(1, hours);
    }

    static String flagOf(NpcId npc) {
        return "wanderer/" + npc.id().name();
    }

    // ------------------------------------------------------------------ placement
    // (NpcManager.Placement)

    @Override
    public Optional<Spot> spot(NpcDef def) {
        if (!def.wanderer()) {
            return Optional.ofNullable(def.location());
        }
        State state = ensure(def);
        NpcDef.Schedule schedule = def.schedule();
        if (!state.visible || state.candidate == null || schedule == null) {
            return Optional.empty();
        }
        return schedule.candidate(state.candidate).map(NpcDef.Candidate::location);
    }

    private State ensure(NpcDef def) {
        State state = states.get(def.id());
        if (state == null) {
            state = new State();
            Target t = target(def, balance());
            state.slot = t.slot();
            state.candidate = t.candidate() == null ? null : t.candidate().id();
            state.forced = t.forced();
            state.visible = t.candidate() != null && visible(t.candidate());
            states.put(def.id(), state);
        }
        return state;
    }

    private record Target(long slot, NpcDef.@Nullable Candidate candidate, boolean forced) {}

    private Target target(NpcDef def, NpcBalance balance) {
        NpcDef.Schedule schedule = def.schedule();
        long slot = WandererSchedule.slot(Instant.now(), zone(), slotHours(def, balance));
        if (schedule == null) {
            return new Target(slot, null, false);
        }
        Optional<String> forced = api.flags().serverValue(flagOf(def.id()));
        if (forced.isPresent()) {
            Optional<NpcDef.Candidate> c = schedule.candidate(forced.get());
            if (c.isPresent()) {
                return new Target(slot, c.get(), true);
            }
        }
        NpcDef.Candidate picked =
                WandererSchedule.pick(
                                def.id().toString(),
                                api.infra().season().value(),
                                slot,
                                schedule.candidates())
                        .orElse(null);
        return new Target(slot, picked, false);
    }

    /** Conditional candidates stand only when that world's time of day and weather match. */
    private boolean visible(NpcDef.Candidate candidate) {
        if (!candidate.conditional()) {
            return true;
        }
        Optional<World> world = worlds.world(candidate.location().world());
        if (world.isEmpty()) {
            return false;
        }
        World w = world.get();
        return WandererSchedule.visible(
                candidate,
                Conditions.timeKey(w.getTime()),
                Conditions.weatherKey(w.isThundering(), w.hasStorm()));
    }

    // ------------------------------------------------------------------ periodic checks

    private void tick() {
        NpcBalance balance = balance();
        ticks += 100;
        boolean checkVisibility = ticks >= balance.wandererCheckTicks();
        if (checkVisibility) {
            ticks = 0;
        }
        for (NpcDef def : npcs.defs()) {
            if (def.wanderer()) {
                evaluate(def, balance, checkVisibility);
            }
        }
    }

    private void evaluate(NpcDef def, NpcBalance balance, boolean checkVisibility) {
        State state = ensure(def);
        Target t = target(def, balance);
        String targetId = t.candidate() == null ? null : t.candidate().id();
        if (!java.util.Objects.equals(targetId, state.candidate)) {
            Instant now = Instant.now();
            if (state.visible && dialogs.hasSessionWith(def.id())) {
                if (state.pendingSince == null) {
                    state.pendingSince = now;
                }
                if (state.pendingSince
                        .plusSeconds(balance.wandererMaxDelaySeconds())
                        .isAfter(now)) {
                    return; // Wait for the dialog to end
                }
            }
            state.pendingSince = null;
            state.slot = t.slot();
            state.candidate = targetId;
            state.forced = t.forced();
            state.visible = t.candidate() != null && visible(t.candidate());
            npcs.despawn(def.id(), false);
            npcs.sync(def.id());
            moved(def, state, true);
            return;
        }
        state.slot = t.slot();
        state.forced = t.forced();
        if (checkVisibility && t.candidate() != null) {
            boolean now = visible(t.candidate());
            if (now != state.visible) {
                state.visible = now;
                if (now) {
                    npcs.sync(def.id());
                } else {
                    npcs.despawn(def.id(), true); // Vanishes with a puff of fog
                }
                moved(def, state, now);
            }
        }
    }

    private void moved(NpcDef def, State state, boolean arrival) {
        Location at = currentLocation(def, state);
        Bukkit.getPluginManager()
                .callEvent(new WandererMovedEvent(def.id(), state.candidate, at, state.visible));
        NpcDef.Schedule schedule = def.schedule();
        if (announce
                && arrival
                && state.visible
                && schedule != null
                && schedule.announce()
                && state.candidate != null) {
            try {
                Component msg =
                        api.messages()
                                .get(
                                        "npc.wanderer.arrived",
                                        Placeholder.component(
                                                "npc",
                                                MiniMessage.miniMessage().deserialize(def.name())),
                                        Placeholder.unparsed("place", placeName(state.candidate)));
                api.bus()
                        .publish(
                                Channels.ANNOUNCEMENTS,
                                new Channels.Announcement(
                                        MiniMessage.miniMessage().serialize(msg)));
            } catch (RuntimeException e) {
                log.warn("떠돌이 도착 공지 실패 ({})", def.id(), e);
            }
        }
    }

    private String placeName(String candidate) {
        String key = "npc.wanderer.place." + candidate;
        return api.messages().has(key)
                ? MiniMessage.miniMessage().serialize(api.messages().get(key))
                : candidate;
    }

    private @Nullable Location currentLocation(NpcDef def, State state) {
        NpcDef.Schedule schedule = def.schedule();
        if (schedule == null || state.candidate == null) {
            return null;
        }
        return schedule.candidate(state.candidate)
                .flatMap(c -> worlds.location(c.location()))
                .orElse(null);
    }

    @EventHandler
    public void onServerFlag(ServerFlagChangedEvent event) {
        if (!event.flag().startsWith("wanderer/")) {
            return;
        }
        NpcBalance balance = balance();
        for (NpcDef def : npcs.defs()) {
            if (def.wanderer() && event.flag().equals(flagOf(def.id()))) {
                // Forced moves happen immediately even during dialog (admin command)
                State state = ensure(def);
                state.pendingSince = Instant.EPOCH;
                evaluate(def, balance, true);
            }
        }
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        states.clear();
    }

    // ------------------------------------------------------------------ WandererService

    @Override
    public Optional<WandererState> current(NpcId npc) {
        Optional<NpcDef> def = npcs.def(npc).filter(NpcDef::wanderer);
        if (def.isEmpty()) {
            return Optional.empty();
        }
        State state = ensure(def.get());
        Instant next =
                WandererSchedule.slotEnd(state.slot, zone(), slotHours(def.get(), balance()));
        return Optional.of(
                new WandererState(
                        npc,
                        state.candidate,
                        currentLocation(def.get(), state),
                        state.visible,
                        next,
                        state.forced));
    }

    @Override
    public boolean forceMove(NpcId npc, String candidate) {
        Optional<NpcDef> def = npcs.def(npc).filter(NpcDef::wanderer);
        NpcDef.Schedule schedule = def.map(NpcDef::schedule).orElse(null);
        if (schedule == null || schedule.candidate(candidate).isEmpty()) {
            return false;
        }
        var _ = api.flags().setServer(flagOf(npc), candidate, false);
        return true;
    }

    @Override
    public void reset(NpcId npc) {
        var _ = api.flags().unsetServer(flagOf(npc));
    }
}
