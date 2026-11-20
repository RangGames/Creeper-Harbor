package wiki.creeper.harbor.boss;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.BossRecordService;
import wiki.creeper.harbor.boss.api.BossService;
import wiki.creeper.harbor.boss.api.WorldBossService;
import wiki.creeper.harbor.boss.command.BossCommands;
import wiki.creeper.harbor.boss.command.PartyCommands;
import wiki.creeper.harbor.boss.content.BossContent;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.boss.data.BossRecords;
import wiki.creeper.harbor.boss.encounter.EncounterManager;
import wiki.creeper.harbor.boss.instance.InstanceManager;
import wiki.creeper.harbor.boss.party.PartyManager;
import wiki.creeper.harbor.boss.world.WorldBossManager;
import wiki.creeper.harbor.common.id.BossId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.core.api.PartyService;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.DialogService;
import wiki.creeper.harbor.npc.api.NpcService;

/**
 * Harbor-Boss (docs 20 10장): 보스 정의, 조우 수명, 경기장(field·instance·world), 보스 리그·부위 판정, 패턴 예고, 페이즈 상태
 * 기계, 최후의 줄다리기 QTE, 파티(Core PartyService 구현), 기여도, 전리품, 최단 기록, 월드 보스, 레이드 인스턴스.
 */
public final class HarborBossPlugin extends HarborModule {

    private @Nullable EncounterManager encounters;
    private @Nullable WorldBossManager world;

    @Override
    public String moduleId() {
        return "boss";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        ctx.migrate();
        BossContent.TYPES.forEach(ctx::contentType);
        ctx.contentCheck(BossDef.TYPE, BossContent.RIG_CHECK);
        ctx.contentCheck(BossDef.TYPE, BossContent.FISH_CHECK);
        ctx.playerComponent(EncounterManager.PROFILE);

        FishingService fishing =
                Objects.requireNonNull(
                        getServer().getServicesManager().load(FishingService.class),
                        "Harbor-Fishing이 켜져 있어야 합니다");
        EncounterManager[] holder = new EncounterManager[1];
        PartyManager parties =
                new PartyManager(uuid -> holder[0] != null && holder[0].inEncounter(uuid));
        EncounterManager mgr =
                new EncounterManager(api, this, ctx.tasks(), getSLF4JLogger(), parties, fishing);
        holder[0] = mgr;
        encounters = mgr;
        WorldBossManager worldBoss = new WorldBossManager(api, ctx.tasks(), getSLF4JLogger(), mgr);
        world = worldBoss;
        InstanceManager instances =
                new InstanceManager(api, ctx.tasks(), getSLF4JLogger(), mgr, parties);
        mgr.setWorldHooks(worldBoss);
        mgr.setInstanceHooks(instances);
        ctx.listen(mgr.listener());
        ctx.listen(instances);
        ctx.listen(worldBoss);
        ctx.handoffBlocker(
                (player, destination) ->
                        mgr.blocksHandoff(player)
                                ? new HandoffBlocker.Deny("encounter")
                                : HandoffBlocker.ALLOW);
        mgr.start();
        worldBoss.start();
        instances.start();

        BossRecords records = new BossRecords(api.database(), ctx.tasks().main());
        var services = getServer().getServicesManager();
        services.register(PartyService.class, parties, this, ServicePriority.Normal);
        services.register(BossService.class, mgr, this, ServicePriority.Normal);
        services.register(BossRecordService.class, records, this, ServicePriority.Normal);
        services.register(WorldBossService.class, worldBoss, this, ServicePriority.Normal);

        BossCommands commands = new BossCommands(api, mgr, records, worldBoss, instances);
        PartyCommands partyCommands = new PartyCommands(api, parties);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> {
                            commands.registerPlayerCommands(event.registrar());
                            partyCommands.register(event.registrar());
                        });

        // NPC(선택): 보스전 중 NPC 클릭·컷씬 막기, 레이드 입장 대화 액션 boss:enter (인자 boss)
        if (getServer().getPluginManager().getPlugin("Harbor-Npc") != null) {
            hookNpc(api, mgr, instances);
        }
        // Shop(선택, Boss보다 뒤에 켜짐): 보스전 중에는 코스메틱 펫·선박 장식을 숨긴다
        // 예약 작업은 서버 시작(모든 플러그인 enable)이 끝난 뒤 첫 틱에 돈다
        var _ =
                ctx.tasks()
                        .later(
                                1,
                                () -> {
                                    if (getServer()
                                            .getPluginManager()
                                            .isPluginEnabled("Harbor-Shop")) {
                                        ShopHook.register(this, mgr);
                                    }
                                });
    }

    /** Shop 클래스는 Shop이 있을 때만 불러온다. */
    private static final class ShopHook {
        static void register(HarborBossPlugin plugin, EncounterManager mgr) {
            var service =
                    plugin.getServer()
                            .getServicesManager()
                            .load(wiki.creeper.harbor.shop.api.CosmeticService.class);
            if (service != null) {
                service.registerPetSuppressor(plugin, p -> mgr.inEncounter(p.getUniqueId()));
            }
        }
    }

    private void hookNpc(HarborApi api, EncounterManager mgr, InstanceManager instances) {
        Optional<NpcService> npc = api.service(NpcService.class);
        npc.ifPresent(
                n -> n.registerBusyCheck(this, "encounter", p -> mgr.inEncounter(p.getUniqueId())));
        api.service(DialogService.class)
                .ifPresent(
                        dialogs ->
                                dialogs.registerAction(
                                        this,
                                        "boss:enter",
                                        (dctx, args) -> {
                                            String raw =
                                                    args.string("boss").or(args::value).orElse("");
                                            try {
                                                HarborId id =
                                                        HarborId.parse(
                                                                raw.contains("/")
                                                                        ? raw
                                                                        : BossId.PREFIX + raw);
                                                InstanceManager.EnterResult r =
                                                        instances.requestEntry(
                                                                dctx.player(), new BossId(id));
                                                if (r == InstanceManager.EnterResult.REQUESTED) {
                                                    return ActionResult.CLOSE;
                                                }
                                                return ActionResult.failMessage(
                                                        "boss.instance."
                                                                + r.name()
                                                                        .toLowerCase(
                                                                                java.util.Locale
                                                                                        .ROOT));
                                            } catch (IllegalArgumentException e) {
                                                return ActionResult.failMessage(
                                                        "boss.cmd.unknown_boss");
                                            }
                                        }));
    }

    @Override
    protected void disable() {
        WorldBossManager w = world;
        if (w != null) {
            w.shutdown();
        }
        EncounterManager m = encounters;
        if (m != null) {
            m.shutdown();
        }
        getServer().getServicesManager().unregisterAll(this);
    }
}
