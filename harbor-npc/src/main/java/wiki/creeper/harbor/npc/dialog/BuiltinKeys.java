package wiki.creeper.harbor.npc.dialog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.content.type.Messages;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.npc.affinity.AffinityManager;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.AffinitySource;
import wiki.creeper.harbor.npc.api.ArgCheck;
import wiki.creeper.harbor.npc.api.CutsceneService;
import wiki.creeper.harbor.npc.api.DialogArgs;
import wiki.creeper.harbor.npc.api.DialogContext;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.ReputationService;
import wiki.creeper.harbor.npc.content.Conditions;
import wiki.creeper.harbor.npc.content.CutsceneDef;

/**
 * NPC built-in dialog keys (docs 20 5.2, 5.4).
 *
 * <ul>
 *   <li>Actions: goto, page, close, affinity:add, flag:set, flag:unset, gift:open, cutscene:play,
 *       sound, message, server:depart (set sail), server:lobby (to the lobby)
 *   <li>Conditions: affinity_min, rep_tier_min, flag, not_flag, server_flag, not_server_flag,
 *       job_tier, level_min, time, weather
 * </ul>
 */
public final class BuiltinKeys {

    private static final Pattern FLAG = FlagService.NAME;
    private static final Pattern SOUND = Pattern.compile("([a-z0-9_.-]+:)?[a-z0-9_./-]+");

    private BuiltinKeys() {}

    public static void register(
            Plugin plugin,
            HarborApi api,
            DialogService dialogs,
            AffinityManager affinity,
            ReputationService reputation,
            CutsceneService cutscenes) {
        // ---------------------------------------------------------------- actions
        dialogs.registerAction(
                plugin,
                "goto",
                new ValidatedAction(
                        (ctx, args) -> ActionResult.goTo(args.value().orElse(ctx.node())),
                        (args, check) -> {
                            if (args.value().isEmpty()) {
                                check.error("이동할 노드가 필요합니다 (goto: <노드>)");
                            }
                        }));
        dialogs.registerAction(
                plugin,
                "page",
                new ValidatedAction(
                        (ctx, args) -> new ActionResult.Page(args.integer("value", 0)),
                        (args, check) -> {
                            if (!args.isInteger("value")) {
                                check.error("페이지 번호(0부터)가 필요합니다 (page: 1)");
                            }
                        }));
        dialogs.registerAction(plugin, "close", (ctx, args) -> ActionResult.CLOSE);
        // Server moves (proposed): depart = main server queue from the lobby, lobby = go to the
        // lobby leaving a seat grace period. Closes the dialog
        dialogs.registerAction(
                plugin,
                "server:depart",
                (ctx, args) -> {
                    api.proxy().requestDepart(ctx.player().getUniqueId());
                    return ActionResult.CLOSE;
                });
        dialogs.registerAction(
                plugin,
                "server:lobby",
                (ctx, args) -> {
                    var _ = api.proxy().requestLobby(ctx.player().getUniqueId(), "npc");
                    return ActionResult.CLOSE;
                });
        dialogs.registerAction(
                plugin,
                "affinity:add",
                new ValidatedAction(
                        (ctx, args) -> {
                            NpcId npc = args.string("npc").map(NpcId::parse).orElse(ctx.npc());
                            String once = args.string("once").orElseThrow();
                            String flag = "affinity_once/" + once;
                            if (api.flags().hasPlayer(ctx.player().getUniqueId(), flag)) {
                                return ActionResult.CONTINUE;
                            }
                            UUID uuid = ctx.player().getUniqueId();
                            int value = args.integer("value", 0);
                            // grant only after the flag is saved (if saving failed it could be
                            // received repeatedly all
                            // season). The cache changes at once, so rapid repeats are blocked
                            var _ =
                                    api.flags()
                                            .setPlayer(uuid, FlagService.Scope.SEASON, flag)
                                            .thenRun(
                                                    () ->
                                                            affinity.add(
                                                                    uuid,
                                                                    npc,
                                                                    value,
                                                                    AffinitySource.DIALOG));
                            return ActionResult.CONTINUE;
                        },
                        (args, check) -> {
                            if (!args.isInteger("value")) {
                                check.error("value(정수)가 필요합니다");
                            }
                            // Force the once flag so repeating the dialog cannot raise affinity
                            // indefinitely
                            Optional<String> once = args.string("once");
                            if (once.isEmpty() || !FLAG.matcher(once.get()).matches()) {
                                check.error("once(플래그 이름, 시즌에 한 번)가 필요합니다");
                            }
                            npcArg(args, check);
                        }));
        dialogs.registerAction(
                plugin,
                "flag:set",
                new ValidatedAction(
                        (ctx, args) -> {
                            FlagService.Scope scope =
                                    args.string("scope", "perm").equals("season")
                                            ? FlagService.Scope.SEASON
                                            : FlagService.Scope.PERM;
                            var _ =
                                    api.flags()
                                            .setPlayer(
                                                    ctx.player().getUniqueId(),
                                                    scope,
                                                    flagArg(args),
                                                    args.string("set_value", "1"));
                            return ActionResult.CONTINUE;
                        },
                        (args, check) -> {
                            flagCheck(args, check);
                            String scope = args.string("scope", "perm");
                            if (!scope.equals("perm") && !scope.equals("season")) {
                                check.error("scope는 perm 또는 season이어야 합니다: " + scope);
                            }
                        }));
        dialogs.registerAction(
                plugin,
                "flag:unset",
                new ValidatedAction(
                        (ctx, args) -> {
                            var _ =
                                    api.flags()
                                            .unsetPlayer(ctx.player().getUniqueId(), flagArg(args));
                            return ActionResult.CONTINUE;
                        },
                        BuiltinKeys::flagCheck));
        dialogs.registerAction(
                plugin, "gift:open", (ctx, args) -> affinity.openGift(ctx.player(), ctx.npc()));
        dialogs.registerAction(
                plugin,
                "cutscene:play",
                new ValidatedAction(
                        (ctx, args) -> {
                            HarborId id =
                                    HarborId.parse(args.string("id").or(args::value).orElseThrow());
                            Optional<String> after = args.string("after_node");
                            Player player = ctx.player();
                            NpcId npc = ctx.npc();
                            player.closeDialog();
                            var _ =
                                    cutscenes
                                            .play(player, id)
                                            .thenAccept(
                                                    result -> {
                                                        if (after.isPresent()
                                                                && player.isOnline()
                                                                && result.completed()) {
                                                            dialogs.open(player, npc, after.get());
                                                        }
                                                    });
                            return ActionResult.HANDOFF;
                        },
                        (args, check) -> {
                            Optional<HarborId> id =
                                    args.string("id").or(args::value).flatMap(HarborId::tryParse);
                            if (id.isEmpty() || !id.get().hasPrefix(CutsceneDef.PREFIX)) {
                                check.error("컷씬 ID(cutscene/<이름>)가 필요합니다");
                            }
                        }));
        dialogs.registerAction(
                plugin,
                "sound",
                new ValidatedAction(
                        (ctx, args) -> {
                            // harbor: sounds fall back to a substitute depending on pack usage
                            // (Core HarborSounds)
                            HarborSounds.play(
                                    ctx.player(),
                                    args.string("sound").or(args::value).orElseThrow(),
                                    HarborSounds.Options.DEFAULT
                                            .withSource(Sound.Source.NEUTRAL)
                                            .withVolume((float) args.number("volume", 1))
                                            .withPitch((float) args.number("pitch", 1)));
                            return ActionResult.CONTINUE;
                        },
                        (args, check) -> {
                            Optional<String> sound = args.string("sound").or(args::value);
                            if (sound.isEmpty() || !SOUND.matcher(sound.get()).matches()) {
                                check.error("사운드 키가 필요합니다 (sound: minecraft:entity.villager.yes)");
                            }
                        }));
        dialogs.registerAction(
                plugin,
                "message",
                new ValidatedAction(
                        (ctx, args) -> {
                            api.messages()
                                    .send(
                                            ctx.player(),
                                            args.string("key").or(args::value).orElseThrow(),
                                            Placeholder.unparsed("player", ctx.player().getName()));
                            return ActionResult.CONTINUE;
                        },
                        (args, check) -> {
                            Optional<String> key = args.string("key").or(args::value);
                            if (key.isEmpty()) {
                                check.error("문구 키가 필요합니다 (message: npc.xxx)");
                            } else if (check.snapshot()
                                    .single(Messages.TYPE)
                                    .get(key.get())
                                    .isEmpty()) {
                                check.error("문구 키가 messages_ko.yml에 없습니다: " + key.get());
                            }
                        }));

        // ---------------------------------------------------------------- conditions
        dialogs.registerCondition(
                plugin,
                "affinity_min",
                new ValidatedCondition(
                        (ctx, args) -> {
                            NpcId npc = args.string("npc").map(NpcId::parse).orElse(ctx.npc());
                            var uuid = ctx.player().getUniqueId();
                            if (args.has("level")) {
                                return affinity.level(uuid, npc) >= args.integer("level", 0);
                            }
                            return affinity.points(uuid, npc) >= args.integer("value", 0);
                        },
                        (args, check) -> {
                            if (!args.isInteger("value") && !args.isInteger("level")) {
                                check.error("value(호감도) 또는 level(단계)이 필요합니다");
                            }
                            npcArg(args, check);
                        }));
        dialogs.registerCondition(
                plugin,
                "rep_tier_min",
                new ValidatedCondition(
                        (ctx, args) ->
                                reputation.tier(ctx.player().getUniqueId())
                                        >= args.integer("value", 1),
                        BuiltinKeys::requireInt));
        dialogs.registerCondition(
                plugin,
                "flag",
                new ValidatedCondition(
                        (ctx, args) ->
                                api.flags().hasPlayer(ctx.player().getUniqueId(), flagArg(args)),
                        BuiltinKeys::flagCheck));
        dialogs.registerCondition(
                plugin,
                "not_flag",
                new ValidatedCondition(
                        (ctx, args) ->
                                !api.flags().hasPlayer(ctx.player().getUniqueId(), flagArg(args)),
                        BuiltinKeys::flagCheck));
        dialogs.registerCondition(
                plugin,
                "server_flag",
                new ValidatedCondition(
                        (ctx, args) -> api.flags().hasServer(flagArg(args)),
                        BuiltinKeys::flagCheck));
        dialogs.registerCondition(
                plugin,
                "not_server_flag",
                new ValidatedCondition(
                        (ctx, args) -> !api.flags().hasServer(flagArg(args)),
                        BuiltinKeys::flagCheck));
        dialogs.registerCondition(
                plugin,
                "job_tier",
                new ValidatedCondition(
                        (ctx, args) -> {
                            int tier = api.players().get(ctx.player()).jobTier().level();
                            int min = args.integer("min", args.integer("value", 1));
                            int max = args.integer("max", 3);
                            return tier >= min && tier <= max;
                        },
                        (args, check) -> {
                            if (!args.isInteger("value")
                                    && !args.isInteger("min")
                                    && !args.isInteger("max")) {
                                check.error("value(최소 단계) 또는 min·max가 필요합니다");
                            }
                        }));
        dialogs.registerCondition(
                plugin,
                "level_min",
                new ValidatedCondition(
                        (ctx, args) ->
                                api.players().get(ctx.player()).level() >= args.integer("value", 1),
                        BuiltinKeys::requireInt));
        dialogs.registerCondition(
                plugin,
                "time",
                new ValidatedCondition(
                        (ctx, args) -> {
                            World w = ctx.player().getWorld();
                            return Conditions.matches(
                                    args.strings("value"), Conditions.timeKey(w.getTime()));
                        },
                        (args, check) -> keyList(args, Conditions.TIMES, "시간대", check)));
        dialogs.registerCondition(
                plugin,
                "weather",
                new ValidatedCondition(
                        (ctx, args) -> {
                            World w = ctx.player().getWorld();
                            return Conditions.matches(
                                    args.strings("value"),
                                    Conditions.weatherKey(w.isThundering(), w.hasStorm()));
                        },
                        (args, check) -> keyList(args, Conditions.WEATHERS, "날씨", check)));
    }

    private static String flagArg(DialogArgs args) {
        return args.string("flag").or(args::value).orElse("");
    }

    private static void flagCheck(DialogArgs args, ArgCheck check) {
        String flag = flagArg(args);
        if (!FLAG.matcher(flag).matches()) {
            check.error("플래그 이름이 필요합니다 (소문자·숫자·밑줄, '/' 구분): '" + flag + "'");
        }
    }

    private static void requireInt(DialogArgs args, ArgCheck check) {
        if (!args.isInteger("value")) {
            check.error("value(정수)가 필요합니다");
        }
    }

    private static void npcArg(DialogArgs args, ArgCheck check) {
        args.string("npc")
                .ifPresent(
                        raw -> {
                            Optional<HarborId> id = HarborId.tryParse(raw);
                            if (id.isEmpty() || !id.get().hasPrefix(NpcId.PREFIX)) {
                                check.error("npc는 NPC ID여야 합니다: " + raw);
                            }
                        });
    }

    private static void keyList(
            DialogArgs args, java.util.Set<String> allowed, String what, ArgCheck check) {
        List<String> values = args.strings("value");
        if (values.isEmpty()) {
            check.error(what + " 값이 필요합니다");
        }
        values.stream()
                .filter(v -> !allowed.contains(v))
                .forEach(v -> check.error(what + " 값이 틀렸습니다: '" + v + "'"));
    }

    /** An action with a check (helper for building one from two lambdas). */
    private record ValidatedAction(
            wiki.creeper.harbor.npc.api.DialogAction body,
            java.util.function.BiConsumer<DialogArgs, ArgCheck> validator)
            implements wiki.creeper.harbor.npc.api.DialogAction {

        @Override
        public ActionResult run(DialogContext ctx, DialogArgs args) {
            return body.run(ctx, args);
        }

        @Override
        public void validate(DialogArgs args, ArgCheck check) {
            validator.accept(args, check);
        }
    }

    private record ValidatedCondition(
            wiki.creeper.harbor.npc.api.DialogCondition predicate,
            java.util.function.BiConsumer<DialogArgs, ArgCheck> validator)
            implements wiki.creeper.harbor.npc.api.DialogCondition {

        @Override
        public boolean test(DialogContext ctx, DialogArgs args) {
            return predicate.test(ctx, args);
        }

        @Override
        public void validate(DialogArgs args, ArgCheck check) {
            validator.accept(args, check);
        }
    }
}
