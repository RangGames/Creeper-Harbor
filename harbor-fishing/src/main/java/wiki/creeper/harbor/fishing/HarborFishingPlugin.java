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
import wiki.creeper.harbor.fishing.session.FishingCommands;
import wiki.creeper.harbor.fishing.session.FishingManager;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * Harbor-Fishing (docs 20 3장): 캐스팅 판정, 미니게임, 크기·품질, 미끼·내구도, 잡템, 신화 이벤트, 그림자 연출, 통발, 요리 버프, 매크로 방지.
 * 다른 모듈은 ServicesManager의 {@link FishingService}·{@link TrapService}와 이벤트로 결과를 받는다.
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
        // 파티클 이름은 서버 enum으로만 검사할 수 있다
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
        FoodManager food = new FoodManager(api, FishingManager.COMPONENT, getSLF4JLogger());
        ctx.listen(fishing);
        ctx.listen(fishing.trapManager());
        ctx.listen(food);
        ctx.handoffBlocker(fishing.handoffBlocker());
        fishing.start();
        fishing.trapManager().registerSeasonHook(this);
        FishingCommands commands = new FishingCommands(api, fishing, food);
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
