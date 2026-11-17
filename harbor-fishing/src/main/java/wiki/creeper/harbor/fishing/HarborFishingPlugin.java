package wiki.creeper.harbor.fishing;

import java.util.Objects;
import org.bukkit.plugin.ServicePriority;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.fishing.api.FishingService;
import wiki.creeper.harbor.fishing.session.FishingCommands;
import wiki.creeper.harbor.fishing.session.FishingManager;
import wiki.creeper.harbor.jobs.api.JobsService;

/**
 * Harbor-Fishing (docs 20 3장): 캐스팅 판정, 미니게임, 크기·품질, 미끼·내구도, 매크로 방지. 다른 모듈은 ServicesManager의 {@link
 * FishingService}와 이벤트로 결과를 받는다.
 */
public final class HarborFishingPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "fishing";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        ctx.migrate();
        ctx.playerComponent(FishingManager.COMPONENT);
        JobsService jobs =
                Objects.requireNonNull(
                        getServer().getServicesManager().load(JobsService.class),
                        "Harbor-Jobs가 켜져 있어야 합니다");
        FishingManager fishing = new FishingManager(this, api, ctx.tasks(), jobs, getSLF4JLogger());
        ctx.listen(fishing);
        fishing.start();
        ctx.adminCommand(new FishingCommands(api, fishing).adminCommand());
        getServer()
                .getServicesManager()
                .register(FishingService.class, fishing, this, ServicePriority.Normal);
    }

    @Override
    protected void disable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}
