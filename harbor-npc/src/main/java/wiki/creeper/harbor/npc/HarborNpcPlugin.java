package wiki.creeper.harbor.npc;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Mannequin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.config.ServerRole;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.npc.affinity.AffinityManager;
import wiki.creeper.harbor.npc.api.AffinityService;
import wiki.creeper.harbor.npc.api.CutsceneService;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.NpcMarkerRegistry;
import wiki.creeper.harbor.npc.api.NpcService;
import wiki.creeper.harbor.npc.api.ReputationService;
import wiki.creeper.harbor.npc.api.WandererService;
import wiki.creeper.harbor.npc.command.NpcCommands;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.NpcContent;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.cutscene.CutsceneManager;
import wiki.creeper.harbor.npc.dialog.BuiltinKeys;
import wiki.creeper.harbor.npc.dialog.DialogManager;
import wiki.creeper.harbor.npc.dialog.DialogRegistry;
import wiki.creeper.harbor.npc.dialog.present.Presenters;
import wiki.creeper.harbor.npc.entity.InteractionListener;
import wiki.creeper.harbor.npc.entity.MannequinBody;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.reputation.ReputationManager;
import wiki.creeper.harbor.npc.wanderer.WandererManager;

/**
 * Harbor-NPC (docs 20 ch. 5): NPC entities, dialog tree/condition/action registry, quest markers,
 * affinity and gifts, reputation, wanderer schedules, and the cutscene player. It depends only on
 * Core; Quest, Economy, Jobs, Collection and Boss connect solely by registering dialog actions,
 * conditions and markers in npc.api.
 *
 * <p>NPC entities are spawned only on the main server (role: main). Instance servers use only
 * CutsceneService, reputation and affinity.
 */
public final class HarborNpcPlugin extends HarborModule implements Listener {

    private @Nullable NpcManager npcs;
    private @Nullable CutsceneManager cutscenes;
    private @Nullable DialogRegistry registry;
    private @Nullable DialogManager dialogs;

    @Override
    public String moduleId() {
        return "npc";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        ctx.migrate();
        NpcContent.TYPES.forEach(ctx::contentType);
        ctx.contentCheck(DialogDef.TYPE, NpcContent.DIALOG_MESSAGES);
        ctx.contentCheck(
                wiki.creeper.harbor.npc.content.CutsceneDef.TYPE, NpcContent.CUTSCENE_MESSAGES);
        ctx.contentCheck(NpcDef.TYPE, NpcContent.NPC_MESSAGES);
        ctx.contentCheck(
                NpcDef.TYPE,
                (def, check) -> {
                    String pose = def.pose();
                    // Mannequin pose support is a Phase 1 verification item, so warn and fall back
                    // to the default pose instead of erroring
                    if (pose != null && MannequinBody.pose(pose) == null) {
                        getSLF4JLogger()
                                .warn(
                                        "NPC {}: Mannequin이 지원하지 않는 자세 {} (가능: {}), 기본 자세로 둡니다",
                                        def.id(),
                                        pose,
                                        Mannequin.validPoses());
                    }
                });

        boolean main = api.infra().role() == ServerRole.MAIN;
        Worlds worlds = new Worlds(api);
        DialogRegistry registry = new DialogRegistry(getSLF4JLogger());
        this.registry = registry;
        ctx.contentCheck(DialogDef.TYPE, registry.check());

        NpcManager npcs = new NpcManager(this, api, worlds, getSLF4JLogger(), main);
        this.npcs = npcs;
        DialogManager dialogs =
                new DialogManager(
                        api,
                        ctx.tasks(),
                        npcs,
                        registry,
                        new Presenters(this, api, ctx.tasks(), npcs),
                        getSLF4JLogger());
        this.dialogs = dialogs;
        AffinityManager affinity = new AffinityManager(api, ctx.tasks(), npcs, getSLF4JLogger());
        ctx.playerComponent(affinity.component());
        ReputationManager reputation =
                new ReputationManager(api, ctx.tasks(), affinity.component(), getSLF4JLogger());
        WandererManager wanderers =
                new WandererManager(
                        api, ctx.tasks(), worlds, npcs, dialogs, getSLF4JLogger(), main);
        npcs.setPlacement(wanderers);
        CutsceneManager cutscenes =
                new CutsceneManager(this, api, ctx.tasks(), worlds, npcs, getSLF4JLogger());
        this.cutscenes = cutscenes;

        // During a cutscene, block NPC clicks and other cutscenes, and refuse server moves
        npcs.registerBusyCheck(this, "cutscene", cutscenes::isPlaying);
        ctx.handoffBlocker(cutscenes);

        BuiltinKeys.register(this, api, dialogs, affinity, reputation, cutscenes);

        ctx.listen(npcs);
        ctx.listen(dialogs);
        ctx.listen(reputation);
        ctx.listen(wanderers);
        ctx.listen(cutscenes);
        ctx.listen(new InteractionListener(api, npcs, dialogs, affinity));
        ctx.listen(this);

        var services = getServer().getServicesManager();
        services.register(NpcService.class, npcs, this, ServicePriority.Normal);
        services.register(NpcMarkerRegistry.class, npcs, this, ServicePriority.Normal);
        services.register(DialogService.class, dialogs, this, ServicePriority.Normal);
        services.register(AffinityService.class, affinity, this, ServicePriority.Normal);
        services.register(ReputationService.class, reputation, this, ServicePriority.Normal);
        services.register(WandererService.class, wanderers, this, ServicePriority.Normal);
        services.register(CutsceneService.class, cutscenes, this, ServicePriority.Normal);

        NpcCommands commands =
                new NpcCommands(api, npcs, dialogs, affinity, reputation, wanderers, cutscenes);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        // Core rereads content after enable finishes, so entities and timers start on the first
        // tick
        var _ =
                ctx.tasks()
                        .sync(
                                () -> {
                                    dialogs.start();
                                    if (main) {
                                        wanderers.start();
                                        npcs.start();
                                    }
                                });
    }

    /**
     * After all modules are enabled: warn about dialog keys no module registered (those choices are
     * hidden).
     */
    @EventHandler
    public void onServerLoad(ServerLoadEvent event) {
        DialogRegistry r = registry;
        if (r != null) {
            r.warnMissing(
                    wiki.creeper.harbor.core.api.Harbor.api()
                            .content()
                            .snapshot()
                            .all(DialogDef.TYPE)
                            .values());
        }
    }

    @Override
    protected void disable() {
        DialogManager d = dialogs;
        if (d != null) {
            d.shutdown();
        }
        CutsceneManager c = cutscenes;
        if (c != null) {
            c.shutdown();
        }
        NpcManager n = npcs;
        if (n != null) {
            n.shutdown();
        }
        getServer().getServicesManager().unregisterAll(this);
    }
}
