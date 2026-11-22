package wiki.creeper.harbor.jobs.npc;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.jobs.service.JobsManager;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.CutsceneService;
import wiki.creeper.harbor.npc.api.DialogService;

/**
 * NPC integration (docs 20 0.3, 5.2: Jobs → NPC optional dependency). The guild master rock's
 * dialog actions {@code job:change_line} (line change) · {@code job:reset_skill} (common tree
 * reset) and the job advancement cutscene (CutsceneService, cutscene/job_advance). This class is
 * loaded only when Harbor-Npc is enabled (otherwise NPC api classes are not touched).
 */
public final class JobsNpcHooks {

    /** Job advancement cutscene ID (content/cutscenes/job_advance.yml). */
    static final HarborId ADVANCE_CUTSCENE = HarborId.parse("cutscene/job_advance");

    private JobsNpcHooks() {}

    public static void register(Plugin plugin, HarborApi api, JobsManager jobs) {
        DialogService dialogs = Bukkit.getServicesManager().load(DialogService.class);
        if (dialogs != null) {
            dialogs.registerAction(
                    plugin, "job:change_line", (ctx, args) -> changeLine(api, jobs, ctx.player()));
            dialogs.registerAction(
                    plugin, "job:reset_skill", (ctx, args) -> resetSkill(api, jobs, ctx.player()));
        }
        CutsceneService cutscenes = Bukkit.getServicesManager().load(CutsceneService.class);
        if (cutscenes != null) {
            jobs.setAdvanceCutscene(
                    player -> {
                        if (!cutscenes.exists(ADVANCE_CUTSCENE)) {
                            return false;
                        }
                        var _ = cutscenes.play(player, ADVANCE_CUTSCENE);
                        return true;
                    });
        }
    }

    /**
     * Line change: 2nd tier and above only. Pick another line and confirm the cost →
     * JobsManager.changeLine.
     */
    static ActionResult changeLine(HarborApi api, JobsManager jobs, Player player) {
        HarborPlayer hp = api.players().get(player);
        JobLine current = hp.jobLine().orElse(null);
        if (hp.jobTier() == JobTier.FIRST || current == null) {
            return ActionResult.failMessage("jobs.npc.change_line_tier");
        }
        long cost = jobs.balance().lineChangeCost(hp.jobTier());
        int tier = hp.jobTier().level();
        List<JobLine> lines = new ArrayList<>();
        List<Dialogs.Choice> choices = new ArrayList<>();
        for (JobLine line : JobLine.values()) {
            if (line == current) {
                continue;
            }
            lines.add(line);
            String name = jobs.lineDef(line).map(d -> d.name(tier)).orElse(line.key());
            choices.add(new Dialogs.Choice(Component.text(name), Component.empty()));
        }
        api.dialogs()
                .choose(
                        player,
                        api.messages().get("jobs.npc.change_line_title"),
                        List.of(
                                api.messages()
                                        .get(
                                                "jobs.npc.change_line_body",
                                                Placeholder.unparsed("cost", String.valueOf(cost)),
                                                Placeholder.unparsed(
                                                        "refund",
                                                        String.valueOf(
                                                                Math.round(
                                                                        jobs.balance().refundRatio()
                                                                                * 100))))),
                        choices,
                        2,
                        index -> {
                            JobLine next = lines.get(index);
                            String name =
                                    jobs.lineDef(next).map(d -> d.name(tier)).orElse(next.key());
                            api.dialogs()
                                    .confirm(
                                            player,
                                            api.messages().get("jobs.npc.change_line_title"),
                                            List.of(
                                                    api.messages()
                                                            .get(
                                                                    "jobs.npc.change_line_confirm",
                                                                    Placeholder.unparsed(
                                                                            "job", name),
                                                                    Placeholder.unparsed(
                                                                            "cost",
                                                                            String.valueOf(cost)))),
                                            api.messages().get("jobs.advance.yes"),
                                            api.messages().get("jobs.advance.no"),
                                            ok -> {
                                                if (!ok) {
                                                    return;
                                                }
                                                var _ =
                                                        jobs.changeLine(
                                                                        player.getUniqueId(),
                                                                        next,
                                                                        true)
                                                                .thenAccept(
                                                                        done ->
                                                                                api.messages()
                                                                                        .send(
                                                                                                player,
                                                                                                done
                                                                                                        ? "jobs.npc.change_line_done"
                                                                                                        : "jobs.npc.failed",
                                                                                                Placeholder
                                                                                                        .unparsed(
                                                                                                                "job",
                                                                                                                name)));
                                            });
                        });
        return ActionResult.HANDOFF;
    }

    /** Common tree reset: free once per season, then gold (JobsManager.resetCommon). */
    static ActionResult resetSkill(HarborApi api, JobsManager jobs, Player player) {
        HarborPlayer hp = api.players().get(player);
        boolean free = !jobs.data(hp).freeResetUsed();
        long cost = free ? 0 : jobs.balance().commonResetCost();
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("jobs.npc.reset_title"),
                        List.of(
                                api.messages()
                                        .get(
                                                free
                                                        ? "jobs.npc.reset_free"
                                                        : "jobs.npc.reset_body",
                                                Placeholder.unparsed(
                                                        "cost", String.valueOf(cost)))),
                        api.messages().get("jobs.npc.reset_yes"),
                        api.messages().get("jobs.advance.no"),
                        ok -> {
                            if (!ok) {
                                return;
                            }
                            var _ =
                                    jobs.resetCommon(player.getUniqueId(), true, true)
                                            .thenAccept(
                                                    done ->
                                                            api.messages()
                                                                    .send(
                                                                            player,
                                                                            done
                                                                                    ? "jobs.npc.reset_done"
                                                                                    : "jobs.npc.failed"));
                        });
        return ActionResult.HANDOFF;
    }
}
