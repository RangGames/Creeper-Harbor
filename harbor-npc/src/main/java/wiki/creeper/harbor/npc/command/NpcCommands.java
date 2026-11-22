package wiki.creeper.harbor.npc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.npc.affinity.AffinityManager;
import wiki.creeper.harbor.npc.api.AffinityLevel;
import wiki.creeper.harbor.npc.api.ReputationLogEntry;
import wiki.creeper.harbor.npc.api.ReputationSource;
import wiki.creeper.harbor.npc.api.ReputationTier;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.cutscene.CutsceneManager;
import wiki.creeper.harbor.npc.data.NpcData;
import wiki.creeper.harbor.npc.dialog.DialogManager;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.reputation.ReputationManager;
import wiki.creeper.harbor.npc.wanderer.WandererManager;

/** /평판, /호감도, /harbor npc … (docs 20 5.5). Every admin command is recorded in admin_log. */
public final class NpcCommands {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final HarborApi api;
    private final NpcManager npcs;
    private final AffinityManager affinity;
    private final ReputationManager reputation;
    private final NpcAdminCommands admin;

    public NpcCommands(
            HarborApi api,
            NpcManager npcs,
            DialogManager dialogs,
            AffinityManager affinity,
            ReputationManager reputation,
            WandererManager wanderers,
            CutsceneManager cutscenes) {
        this.api = api;
        this.npcs = npcs;
        this.affinity = affinity;
        this.reputation = reputation;
        this.admin =
                new NpcAdminCommands(
                        api, npcs, dialogs, affinity, reputation, wanderers, cutscenes);
    }

    // ------------------------------------------------------------------ player commands

    public void registerPlayerCommands(Commands commands) {
        commands.register(
                Commands.literal("평판")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .executes(this::reputationView)
                        .build(),
                "항구 평판",
                List.of());
        commands.register(
                Commands.literal("호감도")
                        .requires(src -> src.getExecutor() instanceof Player)
                        .executes(this::affinityView)
                        .build(),
                "NPC 호감도",
                List.of());
    }

    /** /평판: current value, tier, next-tier benefit, recent gains (Dialog). */
    private int reputationView(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            return 0;
        }
        var uuid = player.getUniqueId();
        int value = reputation.value(uuid);
        ReputationTier tier = reputation.tierInfo(reputation.tier(uuid));
        List<Component> body = new ArrayList<>();
        body.add(
                api.messages()
                        .get(
                                "npc.reputation.view.current",
                                Placeholder.unparsed("value", String.valueOf(value)),
                                Placeholder.unparsed("tier", tier.name()),
                                Placeholder.unparsed(
                                        "best",
                                        reputation.tierInfo(reputation.bestTier(uuid)).name())));
        Optional<ReputationTier> next = reputation.nextTier(uuid);
        if (next.isPresent()) {
            String benefit = "npc.reputation.benefit." + next.get().tier();
            body.add(
                    api.messages()
                            .get(
                                    "npc.reputation.view.next",
                                    Placeholder.unparsed("tier", next.get().name()),
                                    Placeholder.unparsed(
                                            "need", String.valueOf(next.get().points() - value)),
                                    Placeholder.component(
                                            "benefit",
                                            api.messages().has(benefit)
                                                    ? api.messages().get(benefit)
                                                    : Component.empty())));
        } else {
            body.add(api.messages().get("npc.reputation.view.max"));
        }
        List<ReputationLogEntry> recent = reputation.recent(uuid);
        if (!recent.isEmpty()) {
            body.add(api.messages().get("npc.reputation.view.recent_header"));
            for (ReputationLogEntry e : recent) {
                body.add(
                        api.messages()
                                .get(
                                        "npc.reputation.view.recent_line",
                                        Placeholder.unparsed(
                                                "time",
                                                TIME.format(e.at().atZone(api.time().zone()))),
                                        Placeholder.component("source", sourceName(e.source())),
                                        Placeholder.unparsed(
                                                "delta", (e.delta() >= 0 ? "+" : "") + e.delta())));
            }
        }
        api.dialogs().notice(player, api.messages().get("npc.reputation.view.title"), body);
        return Command.SINGLE_SUCCESS;
    }

    private Component sourceName(ReputationSource source) {
        String key = "npc.reputation.source." + source.key();
        return api.messages().has(key) ? api.messages().get(key) : Component.text(source.key());
    }

    /** /호감도: affinity points and level for each NPC met. */
    private int affinityView(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getExecutor() instanceof Player player)) {
            return 0;
        }
        NpcData data = api.players().get(player).get(affinity.component());
        List<Component> body = new ArrayList<>();
        for (NpcDef def : npcs.defs()) {
            if (!data.affinity().containsKey(def.id())) {
                continue;
            }
            int points = data.affinity(def.id()).points();
            AffinityLevel level = affinity.levelInfo(player.getUniqueId(), def.id());
            List<AffinityLevel> levels = affinity.levels(def.id());
            String next =
                    level.level() + 1 < levels.size()
                            ? String.valueOf(levels.get(level.level() + 1).points())
                            : "-";
            body.add(
                    api.messages()
                            .get(
                                    "npc.affinity.view.line",
                                    Placeholder.component(
                                            "npc", npcs.displayName(player, def.id())),
                                    Placeholder.unparsed("level", level.name()),
                                    Placeholder.unparsed("points", String.valueOf(points)),
                                    Placeholder.unparsed("next", next)));
        }
        if (body.isEmpty()) {
            body.add(api.messages().get("npc.affinity.view.empty"));
        }
        api.dialogs().notice(player, api.messages().get("npc.affinity.view.title"), body);
        return Command.SINGLE_SUCCESS;
    }

    // ------------------------------------------------------------------ admin commands

    /** /harbor npc … (Core applies harbor.admin.npc; sub-permissions are checked here). */
    public LiteralArgumentBuilder<CommandSourceStack> adminCommand() {
        return admin.build();
    }
}
