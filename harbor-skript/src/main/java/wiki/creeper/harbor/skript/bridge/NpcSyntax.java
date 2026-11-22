package wiki.creeper.harbor.skript.bridge;

import java.util.List;
import java.util.Optional;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.npc.api.AffinityService;
import wiki.creeper.harbor.npc.api.CutsceneService;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.ReputationService;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Npc is enabled: reputation rank, NPC affinity, cutscene playback, opening NPC
 * dialogs.
 */
public final class NpcSyntax {

    private NpcSyntax() {}

    private static <T> Optional<T> service(Class<T> type) {
        return Harbor.api().service(type);
    }

    private static Optional<NpcId> npc(String raw) {
        return Ids.parse(raw, NpcId.PREFIX).map(NpcId::new);
    }

    public static void register(Registrar r) {
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
                Spec.of("harbor affinity level"),
                (e, x) -> {
                    Optional<NpcId> npc = Args.string(e, x, 1).flatMap(NpcSyntax::npc);
                    Optional<AffinityService> aff = service(AffinityService.class);
                    if (npc.isEmpty() || aff.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(e, x, p -> aff.get().level(p.getUniqueId(), npc.get()));
                },
                "[the] harbor affinity [level] of %players% with [npc] %string%");
        r.number(
                Spec.of("harbor affinity points"),
                (e, x) -> {
                    Optional<NpcId> npc = Args.string(e, x, 1).flatMap(NpcSyntax::npc);
                    Optional<AffinityService> aff = service(AffinityService.class);
                    if (npc.isEmpty() || aff.isEmpty()) {
                        return List.of();
                    }
                    return Args.perPlayer(e, x, p -> aff.get().points(p.getUniqueId(), npc.get()));
                },
                "[the] harbor affinity points of %players% with [npc] %string%");
        r.effect(
                Spec.of("play harbor cutscene"),
                (e, x) -> {
                    Optional<HarborId> id =
                            Args.string(e, x, 0).flatMap(s -> Ids.parse(s, "cutscene/"));
                    Optional<CutsceneService> cut = service(CutsceneService.class);
                    if (id.isEmpty() || cut.isEmpty() || !cut.get().exists(id.get())) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        var _ = cut.get().play(p, id.get());
                    }
                },
                "play harbor cutscene %string% (to|for) %players%");
        r.condition(
                Spec.of("harbor cutscene playing"),
                (e, x) ->
                        service(CutsceneService.class)
                                .map(cut -> Args.all(e, x, cut::isPlaying))
                                .orElse(false),
                "%players% (is|are) watching [a] harbor cutscene",
                "%players% (isn't|is not|aren't|are not) watching [a] harbor cutscene");
        r.effect(
                Spec.of("open harbor dialog"),
                (e, x) -> {
                    Optional<NpcId> npc = Args.string(e, x, 0).flatMap(NpcSyntax::npc);
                    Optional<DialogService> dialogs = service(DialogService.class);
                    if (npc.isEmpty() || dialogs.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        dialogs.get().open(p, npc.get());
                    }
                },
                "open harbor dialog [of npc] %string% (to|for) %players%");
    }
}
