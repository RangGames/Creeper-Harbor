package wiki.creeper.harbor.fishing;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Objects;
import org.bukkit.Particle;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.fishing.api.TrapService;
import wiki.creeper.harbor.fishing.content.FishingContent;
import wiki.creeper.harbor.fishing.content.FishingPresets;
import wiki.creeper.harbor.fishing.food.FoodManager;
import wiki.creeper.harbor.fishing.present.CatchPresenter;
import wiki.creeper.harbor.fishing.rod.RodEnchanter;
import wiki.creeper.harbor.fishing.rod.RodMenu;
import wiki.creeper.harbor.fishing.rod.RodNpcHooks;
import wiki.creeper.harbor.fishing.session.FishingCommands;
import wiki.creeper.harbor.fishing.session.FishingManager;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * Harbor-Fishing (docs 20 chapter 3): cast rolls, minigame, size/quality, bait/durability, junk,
 * mythic events, shadow presentation, traps, food buffs, anti-macro. Other modules receive results
 * via {@link FishingService} and {@link TrapService} in the ServicesManager and via events.
 */
public final class HarborFishingPlugin extends HarborModule {

    private @Nullable FishingManager fishing;

    @Override
    public String moduleId() {
        return "fishing";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        ctx.migrate();
        FishingContent.TYPES.forEach(ctx::contentType);
        // Particle names can only be checked against the server enum
        ctx.contentCheck(
                FishingPresets.TYPE,
                (presets, check) ->
                        presets.grades()
                                .forEach(
                                        (grade, preset) -> {
                                            for (FishingPresets.Stage stage :
                                                    new FishingPresets.Stage[] {
                                                        preset.bite(), preset.caught()
                                                    }) {
                                                String name = stage.particle();
                                                if (name != null
                                                        && CatchPresenter.resolve(name) == null) {
                                                    check.error(
                                                            "presets.yml "
                                                                    + grade.key()
                                                                    + ": 알 수 없는 파티클 '"
                                                                    + name
                                                                    + "' ("
                                                                    + Particle.class.getSimpleName()
                                                                    + ")");
                                                }
                                            }
                                        }));
        ctx.playerComponent(FishingManager.COMPONENT);
        JobsService jobs =
                Objects.requireNonNull(
                        getServer().getServicesManager().load(JobsService.class),
                        "Harbor-Jobs가 켜져 있어야 합니다");
        fishing = new FishingManager(this, api, ctx.tasks(), jobs, getSLF4JLogger());
        FoodManager food = fishing.food();
        ctx.listen(fishing);
        ctx.listen(fishing.trapManager());
        ctx.listen(food);
        ctx.handoffBlocker(fishing.handoffBlocker());
        fishing.start();
        fishing.trapManager().registerSeasonHook(this);
        FishingManager f = fishing;
        RodMenu rodMenu = new RodMenu(api, f.rods(), f::busy);
        RodEnchanter enchanter =
                new RodEnchanter(
                        this, api, f.rods(), f::busy, ctx.tasks().main(), getSLF4JLogger());
        var npc = getServer().getPluginManager().getPlugin("Harbor-Npc");
        if (npc != null && npc.isEnabled()) {
            RodNpcHooks.register(this, api, enchanter);
        }
        FishingCommands commands = new FishingCommands(api, ctx.tasks(), f, food, rodMenu);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));
        var services = getServer().getServicesManager();
        services.register(FishingService.class, fishing, this, ServicePriority.Normal);
        services.register(TrapService.class, fishing.trapManager(), this, ServicePriority.Normal);
    }

    @Override
    protected void disable() {
        FishingManager f = fishing;
        if (f != null) {
            f.shutdown();
        }
        getServer().getServicesManager().unregisterAll(this);
    }
}
