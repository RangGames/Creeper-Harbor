package wiki.creeper.harbor.boss.npc;

import java.util.Locale;
import java.util.Optional;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossService.StartResult;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.encounter.EncounterManager;
import wiki.creeper.harbor.boss.instance.InstanceManager;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.ArgCheck;
import wiki.creeper.harbor.npc.api.DialogAction;
import wiki.creeper.harbor.npc.api.DialogArgs;
import wiki.creeper.harbor.npc.api.DialogContext;
import wiki.creeper.harbor.npc.api.DialogService;

/**
 * Boss dialog actions (loaded only when Harbor-Npc is present).
 *
 * <ul>
 *   <li>{@code boss:enter <boss>}: raid instance entry request (docs 20 10.4)
 *   <li>{@code boss:start <boss>} (proposed): starts a field encounter for the speaker's party
 *       without a summon bait ('3. item list' 3: the main chapter 2 gnome encounter is summoned by
 *       the quest; also the chapter 6 story fight boss/trench_lord_story)
 * </ul>
 */
public final class BossDialogActions {

    private BossDialogActions() {}

    public static void register(
            Plugin plugin,
            DialogService dialogs,
            EncounterManager encounters,
            InstanceManager instances) {
        dialogs.registerAction(
                plugin,
                "boss:enter",
                new DialogAction() {
                    @Override
                    public ActionResult run(DialogContext ctx, DialogArgs args) {
                        Optional<BossId> boss = bossArg(args);
                        if (boss.isEmpty()) {
                            return ActionResult.failMessage("boss.cmd.unknown_boss");
                        }
                        InstanceManager.EnterResult r =
                                instances.requestEntry(ctx.player(), boss.get());
                        if (r == InstanceManager.EnterResult.REQUESTED) {
                            return ActionResult.CLOSE;
                        }
                        return ActionResult.failMessage(
                                "boss.instance." + r.name().toLowerCase(Locale.ROOT));
                    }

                    @Override
                    public void validate(DialogArgs args, ArgCheck check) {
                        checkBoss(args, check, null);
                    }
                });
        dialogs.registerAction(
                plugin,
                "boss:start",
                new DialogAction() {
                    @Override
                    public ActionResult run(DialogContext ctx, DialogArgs args) {
                        Optional<BossId> boss = bossArg(args);
                        if (boss.isEmpty()) {
                            return ActionResult.failMessage("boss.cmd.unknown_boss");
                        }
                        StartResult r = encounters.startScripted(boss.get(), ctx.player());
                        if (r == StartResult.STARTED) {
                            return ActionResult.CLOSE;
                        }
                        return ActionResult.failMessage(
                                "boss.start." + r.name().toLowerCase(Locale.ROOT));
                    }

                    @Override
                    public void validate(DialogArgs args, ArgCheck check) {
                        checkBoss(args, check, BossDef.ArenaMode.FIELD);
                    }
                });
    }

    /** Boss ID argument (value or boss:), the boss/ prefix may be omitted. */
    static Optional<BossId> bossArg(DialogArgs args) {
        String raw = args.string("boss").or(args::value).orElse("");
        return HarborId.tryParse(raw.contains("/") ? raw : BossId.PREFIX + raw)
                .filter(id -> id.hasPrefix(BossId.PREFIX))
                .map(BossId::new);
    }

    private static void checkBoss(
            DialogArgs args, ArgCheck check, BossDef.@Nullable ArenaMode mode) {
        Optional<BossId> boss = bossArg(args);
        if (boss.isEmpty()) {
            check.error("보스 ID가 필요합니다 (boss/…)");
            return;
        }
        Optional<BossDef> def = check.snapshot().get(BossDef.TYPE, boss.get().id());
        if (def.isEmpty()) {
            check.error("없는 보스입니다: " + boss.get().id());
            return;
        }
        if (mode != null && def.get().arena().mode() != mode) {
            check.error("필드 경기장 보스만 대화로 시작할 수 있습니다: " + boss.get().id());
        }
    }
}
