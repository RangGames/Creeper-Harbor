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
import wiki.creeper.harbor.boss.party.PartyActions;
import wiki.creeper.harbor.boss.party.PartyManager;
import wiki.creeper.harbor.boss.party.PartyWaypoints;
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
 * Harbor-Boss (docs 20 chapter 10): boss definitions, encounter lifecycle, arenas (field, instance,
 * world), boss rigs and part hit detection, pattern telegraphs, phase state machine, final
 * tug-of-war QTE, parties (Core PartyService implementation), contribution, loot, fastest records,
 * world bosses, raid instances.
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
        PartyActions partyActions = new PartyActions(api, parties);
        services.register(PartyService.class, partyActions, this, ServicePriority.Normal);
        new PartyWaypoints(api, ctx.tasks(), parties).start();
        services.register(BossService.class, mgr, this, ServicePriority.Normal);
        services.register(BossRecordService.class, records, this, ServicePriority.Normal);
        services.register(WorldBossService.class, worldBoss, this, ServicePriority.Normal);

        BossCommands commands = new BossCommands(api, mgr, records, worldBoss, instances);
        PartyCommands partyCommands = new PartyCommands(api, parties, partyActions);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> {
                            commands.registerPlayerCommands(event.registrar());
                            partyCommands.register(event.registrar());
                        });

        // NPC (optional): block NPC clicks and cutscenes during boss fights, raid entry dialog
        // action boss:enter (argument boss)
        if (getServer().getPluginManager().getPlugin("Harbor-Npc") != null) {
            hookNpc(api, mgr, instances);
        }
        // Shop (optional, enabled after Boss): hide cosmetic pets and ship decorations during boss
        // fights. Scheduled tasks run on the first tick after server startup (all plugins enabled)
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

    /** Shop classes are loaded only when Shop is present. */
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
