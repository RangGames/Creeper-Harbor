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
import wiki.creeper.harbor.npc.entity.InteractionListener;
import wiki.creeper.harbor.npc.entity.MannequinBody;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.reputation.ReputationManager;
import wiki.creeper.harbor.npc.wanderer.WandererManager;

/**
 * Harbor-NPC (docs 20 5장): NPC 엔티티, 대화 트리·조건·액션 레지스트리, 퀘스트 표시, 호감도·선물, 평판, 떠돌이 일정, 컷씬 재생기. 의존은
 * Core뿐이고, Quest·Economy·Jobs·Collection·Boss가 npc.api에 대화 액션·조건·표시를 등록하는 방향으로만 연결된다.
 *
 * <p>NPC 엔티티는 본 서버(role: main)에만 세운다. 인스턴스 서버에서는 CutsceneService·평판·호감도만 쓴다.
 */
public final class HarborNpcPlugin extends HarborModule implements Listener {

    private @Nullable NpcManager npcs;
    private @Nullable CutsceneManager cutscenes;
    private @Nullable DialogRegistry registry;

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
                    // Mannequin 자세 지원 범위는 Phase 1 검증 대상이라 오류 대신 경고 후 기본 자세로 둔다
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
                new DialogManager(api, ctx.tasks(), npcs, registry, getSLF4JLogger());
        AffinityManager affinity = new AffinityManager(api, npcs, getSLF4JLogger());
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

        // 컷씬 중에는 NPC 클릭·다른 컷씬 시작을 막고 서버 이동을 거부한다
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

        // 콘텐츠는 enable이 끝난 뒤 Core가 다시 읽으므로, 엔티티·타이머는 첫 틱에 시작한다
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

    /** 모든 모듈이 켜진 뒤: 등록한 모듈이 없는 대화 키를 경고로 남긴다 (그 선택지는 숨김). */
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
