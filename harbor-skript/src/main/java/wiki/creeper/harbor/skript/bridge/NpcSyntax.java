package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.classes.Changer.ChangeMode;
import ch.njol.skript.lang.Expression;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.AffinityService;
import wiki.creeper.harbor.npc.api.AffinitySource;
import wiki.creeper.harbor.npc.api.CutsceneService;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.DialogSession;
import wiki.creeper.harbor.npc.api.DialogStyle;
import wiki.creeper.harbor.npc.api.NpcInfo;
import wiki.creeper.harbor.npc.api.NpcService;
import wiki.creeper.harbor.npc.api.ReputationService;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.npc.api.ReputationTier;
import wiki.creeper.harbor.npc.api.Speech;
import wiki.creeper.harbor.npc.api.SpeechChoice;
import wiki.creeper.harbor.npc.api.WandererService;
import wiki.creeper.harbor.npc.api.WandererState;
import wiki.creeper.harbor.npc.api.event.AffinityChangedEvent;
import wiki.creeper.harbor.npc.api.event.AffinityLevelUpEvent;
import wiki.creeper.harbor.npc.api.event.CutsceneEndEvent;
import wiki.creeper.harbor.npc.api.event.CutsceneStartEvent;
import wiki.creeper.harbor.npc.api.event.DialogEndEvent;
import wiki.creeper.harbor.npc.api.event.DialogOptionSelectedEvent;
import wiki.creeper.harbor.npc.api.event.GiftGivenEvent;
import wiki.creeper.harbor.npc.api.event.NpcInteractEvent;
import wiki.creeper.harbor.npc.api.event.ReputationChangedEvent;
import wiki.creeper.harbor.npc.api.event.ReputationTierChangedEvent;
import wiki.creeper.harbor.npc.api.event.WandererMovedEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.bridge.NpcScriptEvents.DialogActionEvent;
import wiki.creeper.harbor.skript.bridge.NpcScriptEvents.DialogConditionEvent;
import wiki.creeper.harbor.skript.bridge.NpcScriptEvents.DialogScriptEvent;
import wiki.creeper.harbor.skript.bridge.NpcScriptEvents.SpeechChoiceEvent;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Npc is enabled: reputation (tier, best tier, give and take), NPC affinity (read and
 * change), NPC lookup (location, role, nearest, wanderer schedule), dialogs (open a node, close,
 * session, one-liners, lines with choices), script-implemented dialog actions and conditions
 * ({@code skript:action} · {@code skript:check}), cutscenes, and all NPC api events.
 *
 * <p>Affinity and reputation changes go through AffinityService · ReputationService (source OTHER,
 * reference ID {@code skript:<random>}), which apply caps, fire events and save the value.
 */
public final class NpcSyntax {

    /** Dialog action key that fires {@code on harbor dialog action}. */
    static final String ACTION_KEY = "skript:action";

    /** Dialog condition key that fires {@code on harbor dialog condition}. */
    static final String CONDITION_KEY = "skript:check";

    private NpcSyntax() {}

    private static <T> Optional<T> service(Class<T> type) {
        return Harbor.api().service(type);
    }

    private static Optional<NpcId> npc(String raw) {
        return Ids.parse(raw, NpcId.PREFIX).map(NpcId::new);
    }

    private static Optional<NpcId> npcArg(Event e, Expression<?>[] x, int index) {
        return Args.string(e, x, index).flatMap(NpcSyntax::npc);
    }

    private static Optional<HarborId> cutscene(Event e, Expression<?>[] x, int index) {
        return Args.string(e, x, index).flatMap(s -> Ids.parse(s, "cutscene/"));
    }

    private static OfflinePlayer offline(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }

    private static Component mini(String text) {
        return text.isEmpty() ? Component.empty() : MiniMessage.miniMessage().deserialize(text);
    }

    private static String ref() {
        return "skript:" + UUID.randomUUID();
    }

    public static void register(Registrar r) {
        reputation(r);
        affinity(r);
        npcs(r);
        dialogs(r);
        scriptDialogs(r);
        cutscenes(r);
        events(r);
    }

    // ------------------------------------------------------------------ reputation

    private static void reputation(Registrar r) {
        r.number(
                Spec.of("harbor reputation tier"),
                (e, x) ->
                        service(ReputationService.class)
                                .map(
                                        rep ->
                                                Args.<Number>perPlayer(
                                                        e, x, p -> rep.tier(p.getUniqueId())))
                                .orElse(List.of()),
                "[the] harbor reputation tier of %players%",
                "%players%'[s] harbor reputation tier");
        r.number(
                Spec.of("harbor best reputation tier"),
                (e, x) ->
                        service(ReputationService.class)
                                .map(
                                        rep ->
                                                Args.<Number>perPlayer(
                                                        e, x, p -> rep.bestTier(p.getUniqueId())))
                                .orElse(List.of()),
                "[the] harbor best reputation tier of %players%");
        r.text(
                Spec.of("harbor reputation tier name"),
                (e, x) ->
                        service(ReputationService.class)
                                .map(
                                        rep ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                rep.tierInfo(
                                                                                rep.tier(
                                                                                        p
                                                                                                .getUniqueId()))
                                                                        .name()))
                                .orElse(List.of()),
                "[the] harbor reputation tier name of %players%");
        r.number(
                Spec.of("harbor next reputation tier points"),
                (e, x) ->
                        service(ReputationService.class)
                                .map(
                                        rep ->
                                                Args.<Number>perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                rep.nextTier(p.getUniqueId())
                                                                        .map(ReputationTier::points)
                                                                        .orElse(null)))
                                .orElse(List.of()),
                "[the] harbor next reputation tier points of %players%");
        r.effect(
                Spec.of("give harbor reputation"),
                (e, x) -> changeReputation(e, x, 1),
                "harbor give %number% reputation to %players%");
        r.effect(
                Spec.of("take harbor reputation"),
                (e, x) -> changeReputation(e, x, -1),
                "harbor take %number% reputation from %players%");
    }

    private static void changeReputation(Event e, Expression<?>[] x, int sign) {
        Optional<ReputationService> rep = service(ReputationService.class);
        int amount = Args.number(e, x, 0).map(Number::intValue).orElse(0);
        if (rep.isEmpty() || amount <= 0) {
            return;
        }
        for (Player p : Args.players(e, x, 1)) {
            var _ = rep.get().add(p.getUniqueId(), sign * amount, ReputationSource.OTHER, ref());
        }
    }

    // ------------------------------------------------------------------ affinity

    private static void affinity(Registrar r) {
        r.number(
                Spec.of("harbor affinity level"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 1);
                    Optional<AffinityService> aff = service(AffinityService.class);
                    if (npc.isEmpty() || aff.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(e, x, p -> aff.get().level(p.getUniqueId(), npc.get()));
                },
                "[the] harbor affinity [level] of %players% with [npc] %string%");
        DomainExpr.of(Number.class, "harbor affinity points")
                .get(
                        (e, x) -> {
                            Optional<NpcId> npc = npcArg(e, x, 1);
                            Optional<AffinityService> aff = service(AffinityService.class);
                            if (npc.isEmpty() || aff.isEmpty()) {
                                return List.of();
                            }
                            return Args.perPlayer(
                                    e, x, p -> aff.get().points(p.getUniqueId(), npc.get()));
                        })
                .change(
                        Number.class,
                        EnumSet.of(ChangeMode.ADD, ChangeMode.REMOVE, ChangeMode.SET),
                        NpcSyntax::changeAffinity)
                .register(r, "[the] harbor affinity points of %players% with [npc] %string%");
        r.text(
                Spec.of("harbor affinity level name"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 1);
                    Optional<AffinityService> aff = service(AffinityService.class);
                    if (npc.isEmpty() || aff.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(
                            e, x, p -> aff.get().levelInfo(p.getUniqueId(), npc.get()).name());
                },
                "[the] harbor affinity level name of %players% with [npc] %string%");
        r.number(
                Spec.of("harbor affinity discount"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 1);
                    Optional<AffinityService> aff = service(AffinityService.class);
                    if (npc.isEmpty() || aff.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(
                            e, x, p -> aff.get().discountPercent(p.getUniqueId(), npc.get()));
                },
                "[the] harbor affinity discount of %players% with [npc] %string%");
        r.condition(
                Spec.of("harbor affinity unlock"),
                (e, x) -> {
                    String unlock = Args.string(e, x, 1).orElse("");
                    Optional<NpcId> npc = npcArg(e, x, 2);
                    Optional<AffinityService> aff = service(AffinityService.class);
                    return npc.isPresent()
                            && aff.isPresent()
                            && Args.all(
                                    e,
                                    x,
                                    p -> aff.get().hasUnlock(p.getUniqueId(), npc.get(), unlock));
                },
                "%players% (has|have) harbor affinity unlock %string% with [npc] %string%",
                "%players% (doesn't|does not|don't|do not) have harbor affinity unlock %string%"
                        + " with [npc] %string%");
    }

    private static void changeAffinity(
            Event e, Expression<?>[] x, ChangeMode mode, @Nullable Object value) {
        Optional<NpcId> npc = npcArg(e, x, 1);
        Optional<AffinityService> aff = service(AffinityService.class);
        if (npc.isEmpty() || aff.isEmpty() || !(value instanceof Number n)) {
            return;
        }
        int amount = n.intValue();
        for (Player p : Args.players(e, x, 0)) {
            UUID uuid = p.getUniqueId();
            int delta =
                    switch (mode) {
                        case ADD -> amount;
                        case REMOVE -> -amount;
                        case SET -> amount - aff.get().points(uuid, npc.get());
                        default -> 0;
                    };
            if (delta != 0) {
                var _ = aff.get().add(uuid, npc.get(), delta, AffinitySource.OTHER);
            }
        }
    }

    // ------------------------------------------------------------------ NPC lookup

    private static void npcs(Registrar r) {
        r.text(
                Spec.single("harbor npc ids"),
                (e, x) ->
                        service(NpcService.class)
                                .map(s -> s.all().stream().map(i -> i.id().toString()).toList())
                                .orElse(List.of()),
                "[all] [the] harbor npc ids",
                "[all] [the] harbor npcs");
        r.text(
                Spec.single("harbor npc name"),
                (e, x) -> info(e, x).map(i -> List.of(i.name())).orElse(List.of()),
                "[the] harbor npc name of %string%");
        r.text(
                Spec.single("harbor npc role"),
                (e, x) -> info(e, x).map(i -> List.of(i.role().key())).orElse(List.of()),
                "[the] harbor npc role of %string%");
        r.text(
                Spec.single("harbor npc shop"),
                (e, x) -> info(e, x).map(i -> Args.one(str(i.shop()))).orElse(List.of()),
                "[the] harbor npc shop of %string%");
        r.text(
                Spec.single("harbor npc dialog tree"),
                (e, x) -> info(e, x).map(i -> Args.one(str(i.dialog()))).orElse(List.of()),
                "[the] harbor npc dialog [tree] of %string%");
        r.text(
                Spec.single("harbor npc display name"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 0);
                    Optional<NpcService> s = service(NpcService.class);
                    if (npc.isEmpty()
                            || s.isEmpty()
                            || !(Args.single(e, x, 1) instanceof Player viewer)) {
                        return List.of();
                    }
                    return List.of(
                            MiniMessage.miniMessage()
                                    .serialize(s.get().displayName(viewer, npc.get())));
                },
                "[the] harbor npc display name of %string% for %player%");
        r.condition(
                Spec.single("harbor npc spawned"),
                (e, x) -> info(e, x).map(NpcInfo::spawned).orElse(false),
                "harbor npc %string% is spawned",
                "harbor npc %string% (isn't|is not) spawned");
        DomainExpr.of(Location.class, "harbor npc location")
                .single()
                .get(
                        (e, x) -> {
                            Optional<NpcId> npc = npcArg(e, x, 0);
                            Optional<NpcService> s = service(NpcService.class);
                            if (npc.isEmpty() || s.isEmpty()) {
                                return List.of();
                            }
                            return Args.one(s.get().location(npc.get()).orElse(null));
                        })
                .register(r, "[the] harbor npc location of %string%");
        r.text(
                Spec.of("nearest harbor npc"),
                (e, x) -> {
                    Optional<NpcService> s = service(NpcService.class);
                    double radius = Args.number(e, x, 1).map(Number::doubleValue).orElse(0.0);
                    if (s.isEmpty() || radius <= 0) {
                        return List.of();
                    }
                    return Args.perPlayer(
                            e,
                            x,
                            p ->
                                    s.get()
                                            .nearest(p, radius)
                                            .map(i -> i.id().toString())
                                            .orElse(null));
                },
                "[the] nearest harbor npc (to|of) %players% within %number% [blocks]");
        r.condition(
                Spec.of("near harbor npc"),
                (e, x) -> {
                    double radius = Args.number(e, x, 1).map(Number::doubleValue).orElse(0.0);
                    Optional<NpcId> npc = npcArg(e, x, 2);
                    Optional<NpcService> s = service(NpcService.class);
                    return npc.isPresent()
                            && s.isPresent()
                            && Args.all(e, x, p -> s.get().isNear(p, npc.get(), radius));
                },
                "%players% (is|are) within %number% [blocks] of harbor npc %string%",
                "%players% (isn't|is not|aren't|are not) within %number% [blocks] of harbor npc"
                        + " %string%");
        r.text(
                Spec.of("harbor npc busy reason"),
                (e, x) ->
                        service(NpcService.class)
                                .map(s -> Args.perPlayer(e, x, p -> s.busyReason(p).orElse(null)))
                                .orElse(List.of()),
                "[the] harbor npc busy reason of %players%");

        // Wanderers
        r.text(
                Spec.single("harbor wanderer candidate"),
                (e, x) -> wanderer(e, x).map(w -> Args.one(w.candidate())).orElse(List.of()),
                "[the] harbor wanderer (candidate|spot) of %string%");
        DomainExpr.of(Location.class, "harbor wanderer location")
                .single()
                .get((e, x) -> wanderer(e, x).map(w -> Args.one(w.location())).orElse(List.of()))
                .register(r, "[the] harbor wanderer location of %string%");
        r.number(
                Spec.single("harbor wanderer next move"),
                (e, x) ->
                        wanderer(e, x)
                                .map(
                                        w ->
                                                List.<Number>of(
                                                        Math.max(
                                                                0,
                                                                w.nextMoveAt().getEpochSecond()
                                                                        - java.time.Instant.now()
                                                                                .getEpochSecond())))
                                .orElse(List.of()),
                "[the] harbor wanderer next move (seconds|time) of %string%");
        r.effect(
                Spec.single("admin force harbor wanderer"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 0);
                    String candidate = Args.string(e, x, 1).orElse("");
                    Optional<WandererService> w = service(WandererService.class);
                    if (npc.isPresent() && w.isPresent() && !candidate.isEmpty()) {
                        w.get().forceMove(npc.get(), candidate);
                    }
                },
                "harbor admin force wanderer %string% to [candidate] %string%");
        r.effect(
                Spec.single("admin reset harbor wanderer"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 0);
                    Optional<WandererService> w = service(WandererService.class);
                    if (npc.isPresent() && w.isPresent()) {
                        w.get().reset(npc.get());
                    }
                },
                "harbor admin reset wanderer %string%");
    }

    private static Optional<NpcInfo> info(Event e, Expression<?>[] x) {
        Optional<NpcId> npc = npcArg(e, x, 0);
        Optional<NpcService> s = service(NpcService.class);
        return npc.isPresent() && s.isPresent() ? s.get().npc(npc.get()) : Optional.empty();
    }

    private static Optional<WandererState> wanderer(Event e, Expression<?>[] x) {
        Optional<NpcId> npc = npcArg(e, x, 0);
        Optional<WandererService> w = service(WandererService.class);
        return npc.isPresent() && w.isPresent() ? w.get().current(npc.get()) : Optional.empty();
    }

    private static @Nullable String str(@Nullable Object o) {
        return o == null ? null : o.toString();
    }

    // ------------------------------------------------------------------ dialogs

    private static void dialogs(Registrar r) {
        r.effect(
                Spec.of("open harbor dialog"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 0);
                    Optional<DialogService> dialogs = service(DialogService.class);
                    if (npc.isEmpty() || dialogs.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        dialogs.get().open(p, npc.get());
                    }
                },
                "open harbor dialog [of npc] %string% (to|for) %players%");
        r.effect(
                Spec.of("open harbor dialog node"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 0);
                    String node = Args.string(e, x, 1).orElse("");
                    Optional<DialogService> dialogs = service(DialogService.class);
                    if (npc.isEmpty() || dialogs.isEmpty() || node.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 2)) {
                        dialogs.get().open(p, npc.get(), node);
                    }
                },
                "open harbor dialog [of npc] %string% at node %string% (to|for) %players%");
        r.effect(
                Spec.of("close harbor dialog"),
                (e, x) ->
                        service(DialogService.class)
                                .ifPresent(
                                        d -> {
                                            for (Player p : Args.players(e, x, 0)) {
                                                d.close(p);
                                            }
                                        }),
                "close harbor dialog (of|for) %players%");
        r.condition(
                Spec.of("in harbor dialog"),
                (e, x) ->
                        service(DialogService.class)
                                .map(d -> Args.all(e, x, p -> d.session(p).isPresent()))
                                .orElse(false),
                "%players% (is|are) in [a] harbor dialog",
                "%players% (isn't|is not|aren't|are not) in [a] harbor dialog");
        r.text(
                Spec.of("harbor dialog npc"),
                (e, x) -> session(e, x, s -> s.npc().toString()),
                "[the] harbor dialog npc of %players%");
        r.text(
                Spec.of("harbor dialog node"),
                (e, x) -> session(e, x, DialogSession::node),
                "[the] harbor dialog node of %players%");
        r.text(
                Spec.of("harbor dialog tree"),
                (e, x) -> session(e, x, s -> s.dialog().toString()),
                "[the] harbor dialog tree of %players%");
        r.condition(
                Spec.single("harbor npc has dialog session"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 0);
                    Optional<DialogService> d = service(DialogService.class);
                    return npc.isPresent() && d.isPresent() && d.get().hasSessionWith(npc.get());
                },
                "harbor npc %string% is talking",
                "harbor npc %string% (isn't|is not) talking");
        r.effect(
                Spec.of("harbor npc say"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 0);
                    Optional<DialogService> d = service(DialogService.class);
                    if (npc.isEmpty() || d.isEmpty()) {
                        return;
                    }
                    Component line = mini(Args.string(e, x, 1).orElse(""));
                    DialogStyle style =
                            Args.string(e, x, 3).flatMap(DialogStyle::parse).orElse(null);
                    for (Player p : Args.players(e, x, 2)) {
                        d.get().say(p, npc.get(), line, style);
                    }
                },
                "make harbor npc %string% say %string% to %players% [(in|with) style %-string%]");
        r.effect(
                Spec.of("harbor npc present"),
                (e, x) -> {
                    Optional<NpcId> npc = npcArg(e, x, 0);
                    Optional<DialogService> d = service(DialogService.class);
                    if (npc.isEmpty() || d.isEmpty()) {
                        return;
                    }
                    List<Component> pages = components(e, x, 1);
                    if (pages.isEmpty()) {
                        return;
                    }
                    List<SpeechChoice> choices = new ArrayList<>();
                    for (Component c : components(e, x, 3)) {
                        choices.add(SpeechChoice.of(c));
                    }
                    String tag = Args.string(e, x, 4).orElse(null);
                    Speech speech = new Speech(null, null, pages, choices);
                    NpcId id = npc.get();
                    for (Player p : Args.players(e, x, 2)) {
                        d.get()
                                .present(
                                        p,
                                        id,
                                        speech,
                                        choice ->
                                                Bukkit.getPluginManager()
                                                        .callEvent(
                                                                new SpeechChoiceEvent(
                                                                        p, id, tag, choice)));
                    }
                },
                "make harbor npc %string% present %strings% to %players% [with choices %-strings%]"
                        + " [(tagged|with tag) %-string%]");
    }

    private static List<String> session(
            Event e, Expression<?>[] x, java.util.function.Function<DialogSession, String> f) {
        return service(DialogService.class)
                .map(d -> Args.perPlayer(e, x, p -> d.session(p).map(f).orElse(null)))
                .orElse(List.of());
    }

    private static List<Component> components(Event e, Expression<?>[] x, int index) {
        List<Component> out = new ArrayList<>();
        if (index >= x.length || x[index] == null) {
            return out;
        }
        for (Object o : x[index].getArray(e)) {
            out.add(mini(String.valueOf(o)));
        }
        return out;
    }

    // ------------------------------------------------------------------ script dialog keys

    private static void scriptDialogs(Registrar r) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Harbor-Skript");
        Optional<DialogService> dialogs = service(DialogService.class);
        if (plugin != null && dialogs.isPresent()) {
            DialogService d = dialogs.get();
            if (!d.hasAction(ACTION_KEY)) {
                d.registerAction(
                        plugin,
                        ACTION_KEY,
                        (ctx, args) -> {
                            DialogActionEvent event = new DialogActionEvent(ctx, args);
                            Bukkit.getPluginManager().callEvent(event);
                            return event.result();
                        });
            }
            if (!d.hasCondition(CONDITION_KEY)) {
                d.registerCondition(
                        plugin,
                        CONDITION_KEY,
                        (ctx, args) -> {
                            DialogConditionEvent event = new DialogConditionEvent(ctx, args);
                            Bukkit.getPluginManager().callEvent(event);
                            return event.pass();
                        });
            }
        }

        r.event(
                "Harbor Dialog Action",
                DialogActionEvent.class,
                "대화 선택지의 액션 skript:action이 실행될 때 (event-player, event-string = 액션 ID)."
                        + " 결과는 harbor dialog result … 효과로 정한다 (기본: 다음 액션으로)",
                "harbor dialog action");
        r.value(DialogActionEvent.class, Player.class, DialogScriptEvent::player);
        r.value(DialogActionEvent.class, String.class, DialogScriptEvent::id);
        r.event(
                "Harbor Dialog Condition",
                DialogConditionEvent.class,
                "대화 조건 skript:check를 검사할 때 (event-player, event-string = 조건 ID)."
                        + " harbor dialog check pass로 통과시킨다 (기본: 불통과)",
                "harbor dialog condition");
        r.value(DialogConditionEvent.class, Player.class, DialogScriptEvent::player);
        r.value(DialogConditionEvent.class, String.class, DialogScriptEvent::id);
        r.event(
                "Harbor Npc Speech Choice",
                SpeechChoiceEvent.class,
                "make harbor npc … present로 보인 대사에서 고른 뒤 (event-player, event-number = 고른 번호"
                        + " 0부터, 닫으면 -1, event-string = 태그)",
                "harbor npc speech choice");
        r.value(SpeechChoiceEvent.class, Player.class, SpeechChoiceEvent::player);
        r.value(SpeechChoiceEvent.class, Number.class, SpeechChoiceEvent::choice);
        r.value(SpeechChoiceEvent.class, String.class, SpeechChoiceEvent::tag);

        r.text(
                Spec.single("harbor dialog action id"),
                (e, x) -> e instanceof DialogScriptEvent s ? List.of(s.id()) : List.<String>of(),
                "[the] harbor dialog (action|check|condition) id");
        r.text(
                Spec.single("harbor dialog argument"),
                (e, x) -> {
                    if (!(e instanceof DialogScriptEvent s)) {
                        return List.of();
                    }
                    String key = Args.string(e, x, 0).orElse("");
                    return s.args().strings(key);
                },
                "[the] harbor dialog argument %string%");
        r.text(
                Spec.single("harbor dialog current npc"),
                (e, x) -> {
                    if (e instanceof DialogScriptEvent s) {
                        return List.of(s.npc().toString());
                    }
                    return e instanceof SpeechChoiceEvent c
                            ? List.of(c.npc().toString())
                            : List.of();
                },
                "[the] harbor dialog current npc");
        r.text(
                Spec.single("harbor dialog current node"),
                (e, x) -> e instanceof DialogScriptEvent s ? List.of(s.node()) : List.of(),
                "[the] harbor dialog current node");

        r.effect(
                Spec.single("harbor dialog result continue"),
                (e, x) -> result(e, ActionResult.CONTINUE),
                "harbor dialog result continue");
        r.effect(
                Spec.single("harbor dialog result close"),
                (e, x) -> result(e, ActionResult.CLOSE),
                "harbor dialog result close");
        r.effect(
                Spec.single("harbor dialog result handoff"),
                (e, x) -> result(e, ActionResult.HANDOFF),
                "harbor dialog result handoff");
        r.effect(
                Spec.single("harbor dialog result go to node"),
                (e, x) -> Args.string(e, x, 0).ifPresent(n -> result(e, ActionResult.goTo(n))),
                "harbor dialog result go[ ]to node %string%");
        r.effect(
                Spec.single("harbor dialog result go to page"),
                (e, x) ->
                        Args.number(e, x, 0)
                                .ifPresent(
                                        n ->
                                                result(
                                                        e,
                                                        new ActionResult.Page(
                                                                Math.max(0, n.intValue())))),
                "harbor dialog result go[ ]to page %number%");
        r.effect(
                Spec.single("harbor dialog result fail"),
                (e, x) ->
                        result(
                                e,
                                new ActionResult.Fail(
                                        Args.string(e, x, 1).orElse(null),
                                        Args.string(e, x, 0).orElse(null))),
                "harbor dialog result fail [with message %-string%] [(and|then) go[ ]to node"
                        + " %-string%]");
        r.effect(
                Spec.single("harbor dialog check pass"),
                (e, x) -> {
                    if (e instanceof DialogConditionEvent c) {
                        c.pass(true);
                    }
                },
                "harbor dialog check pass[es]");
        r.effect(
                Spec.single("harbor dialog check fail"),
                (e, x) -> {
                    if (e instanceof DialogConditionEvent c) {
                        c.pass(false);
                    }
                },
                "harbor dialog check fail[s]");
    }

    private static void result(Event e, ActionResult result) {
        if (e instanceof DialogActionEvent a) {
            a.result(result);
        }
    }

    // ------------------------------------------------------------------ cutscenes

    private static void cutscenes(Registrar r) {
        r.effect(
                Spec.of("play harbor cutscene"),
                (e, x) -> {
                    Optional<HarborId> id = cutscene(e, x, 0);
                    Optional<CutsceneService> cut = service(CutsceneService.class);
                    if (id.isEmpty() || cut.isEmpty() || !cut.get().exists(id.get())) {
                        return;
                    }
                    List<Player> players = Args.players(e, x, 1);
                    if (players.size() > 1) {
                        var _ = cut.get().playGroup(players, id.get());
                        return;
                    }
                    for (Player p : players) {
                        var _ = cut.get().play(p, id.get());
                    }
                },
                "play harbor cutscene %string% (to|for) %players%");
        r.effect(
                Spec.of("stop harbor cutscene"),
                (e, x) ->
                        service(CutsceneService.class)
                                .ifPresent(
                                        cut -> {
                                            for (Player p : Args.players(e, x, 0)) {
                                                cut.stop(p);
                                            }
                                        }),
                "stop harbor cutscene (of|for) %players%");
        r.condition(
                Spec.of("harbor cutscene playing"),
                (e, x) ->
                        service(CutsceneService.class)
                                .map(cut -> Args.all(e, x, cut::isPlaying))
                                .orElse(false),
                "%players% (is|are) watching [a] harbor cutscene",
                "%players% (isn't|is not|aren't|are not) watching [a] harbor cutscene");
        r.text(
                Spec.of("harbor cutscene playing id"),
                (e, x) ->
                        service(CutsceneService.class)
                                .map(
                                        cut ->
                                                Args.perPlayer(
                                                        e,
                                                        x,
                                                        p ->
                                                                cut.playing(p)
                                                                        .map(HarborId::toString)
                                                                        .orElse(null)))
                                .orElse(List.of()),
                "[the] harbor cutscene of %players%");
        r.condition(
                Spec.of("harbor cutscene seen"),
                (e, x) -> {
                    Optional<HarborId> id = cutscene(e, x, 1);
                    Optional<CutsceneService> cut = service(CutsceneService.class);
                    return id.isPresent()
                            && cut.isPresent()
                            && Args.all(e, x, p -> cut.get().hasSeen(p, id.get()));
                },
                "%players% (has|have) seen harbor cutscene %string%",
                "%players% (hasn't|has not|haven't|have not) seen harbor cutscene %string%");
    }

    // ------------------------------------------------------------------ api events

    private static void events(Registrar r) {
        r.event(
                "Harbor Affinity Change",
                AffinityChangedEvent.class,
                "NPC 호감도가 바뀐 뒤 (event-offlineplayer, event-string = NPC ID, event-number = 변화량)",
                "harbor affinity change");
        r.value(AffinityChangedEvent.class, OfflinePlayer.class, e -> offline(e.player()));
        r.value(AffinityChangedEvent.class, String.class, e -> e.npc().toString());
        r.value(AffinityChangedEvent.class, Number.class, e -> e.newPoints() - e.oldPoints());

        r.event(
                "Harbor Affinity Level Up",
                AffinityLevelUpEvent.class,
                "NPC 호감도 단계가 오른 뒤 (event-offlineplayer, event-string = NPC ID, event-number = 새 단계)",
                "harbor affinity level up");
        r.value(AffinityLevelUpEvent.class, OfflinePlayer.class, e -> offline(e.player()));
        r.value(AffinityLevelUpEvent.class, String.class, e -> e.npc().toString());
        r.value(AffinityLevelUpEvent.class, Number.class, AffinityLevelUpEvent::newLevel);

        r.event(
                "Harbor Cutscene Start",
                CutsceneStartEvent.class,
                "컷씬 재생 직전 (event-player, event-string = 컷씬 ID)",
                "harbor cutscene start");
        r.value(CutsceneStartEvent.class, Player.class, CutsceneStartEvent::player);
        r.value(CutsceneStartEvent.class, String.class, e -> e.cutscene().toString());

        r.event(
                "Harbor Cutscene End",
                CutsceneEndEvent.class,
                "컷씬이 끝난 뒤 (event-player, event-string = 컷씬 ID, harbor cutscene result)",
                "harbor cutscene end");
        r.value(CutsceneEndEvent.class, Player.class, CutsceneEndEvent::player);
        r.value(CutsceneEndEvent.class, String.class, e -> e.cutscene().toString());

        r.event(
                "Harbor Dialog End",
                DialogEndEvent.class,
                "NPC 대화 세션이 끝난 뒤 (event-player, event-string = NPC ID, harbor dialog end reason ·"
                        + " last node)",
                "harbor dialog end");
        r.value(DialogEndEvent.class, Player.class, DialogEndEvent::player);
        r.value(DialogEndEvent.class, String.class, e -> e.npc().toString());

        r.event(
                "Harbor Dialog Option Select",
                DialogOptionSelectedEvent.class,
                "대화 선택지를 고른 뒤, 액션 실행 전 (event-player, event-string = NPC ID, event-number ="
                        + " 선택지 번호 0부터)",
                "harbor dialog option select[ed]");
        r.value(DialogOptionSelectedEvent.class, Player.class, DialogOptionSelectedEvent::player);
        r.value(DialogOptionSelectedEvent.class, String.class, e -> e.npc().toString());
        r.value(DialogOptionSelectedEvent.class, Number.class, DialogOptionSelectedEvent::option);

        r.event(
                "Harbor Npc Gift",
                GiftGivenEvent.class,
                "NPC에게 선물한 뒤 (event-player, event-string = NPC ID, event-number = 호감도 변화,"
                        + " harbor gift item · reaction)",
                "harbor [npc] gift");
        r.value(GiftGivenEvent.class, Player.class, GiftGivenEvent::player);
        r.value(GiftGivenEvent.class, String.class, e -> e.npc().toString());
        r.value(GiftGivenEvent.class, Number.class, GiftGivenEvent::points);

        r.event(
                "Harbor Npc Interact",
                NpcInteractEvent.class,
                "NPC를 클릭했을 때, 대화가 열리기 전 (event-player, event-string = NPC ID). cancel event로"
                        + " 대화를 막는다",
                "harbor npc (interact|click)");
        r.value(NpcInteractEvent.class, Player.class, NpcInteractEvent::player);
        r.value(NpcInteractEvent.class, String.class, e -> e.npc().toString());

        r.event(
                "Harbor Reputation Change",
                ReputationChangedEvent.class,
                "평판이 바뀐 뒤 (event-offlineplayer, event-number = 변화량, event-string = 출처)",
                "harbor reputation change");
        r.value(ReputationChangedEvent.class, OfflinePlayer.class, e -> offline(e.player()));
        r.value(ReputationChangedEvent.class, Number.class, e -> e.newValue() - e.oldValue());
        r.value(ReputationChangedEvent.class, String.class, e -> e.source().key());

        r.event(
                "Harbor Reputation Tier Change",
                ReputationTierChangedEvent.class,
                "평판 등급이 바뀐 뒤 (event-offlineplayer, event-number = 새 등급)",
                "harbor reputation tier change");
        r.value(ReputationTierChangedEvent.class, OfflinePlayer.class, e -> offline(e.player()));
        r.value(
                ReputationTierChangedEvent.class,
                Number.class,
                ReputationTierChangedEvent::newTier);

        r.event(
                "Harbor Wanderer Move",
                WandererMovedEvent.class,
                "떠돌이 NPC가 옮기거나 나타나거나 사라진 뒤 (event-string = NPC ID, event-location = 새 위치)",
                "harbor wanderer move[d]");
        r.value(WandererMovedEvent.class, String.class, e -> e.npc().toString());
        r.value(WandererMovedEvent.class, Location.class, WandererMovedEvent::location);

        // Event-specific values that do not fit an event-value type
        r.text(
                Spec.single("harbor event npc"),
                (e, x) -> Args.one(eventNpc(e)),
                "[the] harbor event npc");
        r.text(
                Spec.in("harbor cutscene result", CutsceneEndEvent.class),
                (e, x) ->
                        e instanceof CutsceneEndEvent c
                                ? List.of(c.result().name().toLowerCase(java.util.Locale.ROOT))
                                : List.of(),
                "[the] harbor cutscene result");
        r.condition(
                Spec.in("harbor cutscene completed", CutsceneEndEvent.class),
                (e, x) -> e instanceof CutsceneEndEvent c && c.result().completed(),
                "[the] harbor cutscene (was|is) completed",
                "[the] harbor cutscene (wasn't|was not|isn't|is not) completed");
        r.text(
                Spec.in("harbor dialog end reason", DialogEndEvent.class),
                (e, x) ->
                        e instanceof DialogEndEvent d
                                ? List.of(d.reason().name().toLowerCase(java.util.Locale.ROOT))
                                : List.of(),
                "[the] harbor dialog end reason");
        r.text(
                Spec.single("harbor event dialog node"),
                (e, x) -> {
                    if (e instanceof DialogEndEvent d) {
                        return List.of(d.lastNode());
                    }
                    return e instanceof DialogOptionSelectedEvent o ? List.of(o.node()) : List.of();
                },
                "[the] harbor (last|event) dialog node");
        r.text(
                Spec.single("harbor event dialog tree"),
                (e, x) -> {
                    if (e instanceof DialogEndEvent d) {
                        return List.of(d.dialog().toString());
                    }
                    return e instanceof DialogOptionSelectedEvent o
                            ? List.of(o.dialog().toString())
                            : List.of();
                },
                "[the] harbor event dialog tree");
        r.text(
                Spec.in("harbor gift item", GiftGivenEvent.class),
                (e, x) -> e instanceof GiftGivenEvent g ? Args.one(str(g.item())) : List.of(),
                "[the] harbor gift item");
        r.text(
                Spec.in("harbor gift reaction", GiftGivenEvent.class),
                (e, x) -> e instanceof GiftGivenEvent g ? List.of(g.reaction().key()) : List.of(),
                "[the] harbor gift reaction");
        r.condition(
                Spec.in("harbor npc right click", NpcInteractEvent.class),
                (e, x) -> e instanceof NpcInteractEvent i && i.rightClick(),
                "[the] harbor npc click is [a] right click",
                "[the] harbor npc click is [a] left click");
        r.text(
                Spec.in("harbor affinity source", AffinityChangedEvent.class),
                (e, x) ->
                        e instanceof AffinityChangedEvent a ? List.of(a.source().key()) : List.of(),
                "[the] harbor affinity source");
        r.number(
                Spec.single("harbor event new value"),
                (e, x) -> {
                    Number v =
                            switch (e) {
                                case AffinityChangedEvent a -> a.newPoints();
                                case ReputationChangedEvent rc -> rc.newValue();
                                case ReputationTierChangedEvent t -> t.newTier();
                                case AffinityLevelUpEvent l -> l.newLevel();
                                default -> null;
                            };
                    return Args.one(v);
                },
                "[the] harbor event new value");
        r.number(
                Spec.single("harbor event old value"),
                (e, x) -> {
                    Number v =
                            switch (e) {
                                case AffinityChangedEvent a -> a.oldPoints();
                                case ReputationChangedEvent rc -> rc.oldValue();
                                case ReputationTierChangedEvent t -> t.oldTier();
                                case AffinityLevelUpEvent l -> l.oldLevel();
                                default -> null;
                            };
                    return Args.one(v);
                },
                "[the] harbor event old value");
        r.number(
                Spec.in("harbor event best tier", ReputationTierChangedEvent.class),
                (e, x) ->
                        e instanceof ReputationTierChangedEvent t
                                ? List.of(t.bestTier())
                                : List.of(),
                "[the] harbor event best [reputation] tier");
        r.text(
                Spec.in("harbor wanderer event candidate", WandererMovedEvent.class),
                (e, x) -> e instanceof WandererMovedEvent w ? Args.one(w.candidate()) : List.of(),
                "[the] harbor event wanderer (candidate|spot)");
        r.condition(
                Spec.in("harbor wanderer visible", WandererMovedEvent.class),
                (e, x) -> e instanceof WandererMovedEvent w && w.visible(),
                "[the] harbor wanderer is visible",
                "[the] harbor wanderer (isn't|is not) visible");
    }

    private static @Nullable String eventNpc(Event e) {
        NpcId id =
                switch (e) {
                    case AffinityChangedEvent a -> a.npc();
                    case AffinityLevelUpEvent a -> a.npc();
                    case DialogEndEvent d -> d.npc();
                    case DialogOptionSelectedEvent d -> d.npc();
                    case GiftGivenEvent g -> g.npc();
                    case NpcInteractEvent i -> i.npc();
                    case WandererMovedEvent w -> w.npc();
                    case DialogScriptEvent s -> s.npc();
                    case SpeechChoiceEvent s -> s.npc();
                    default -> null;
                };
        return id == null ? null : id.toString();
    }
}
