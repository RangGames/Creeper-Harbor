package wiki.creeper.harbor.jobs;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.ServicePriority;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.jobs.api.JobsService;
import wiki.creeper.harbor.jobs.content.JobLineDef;
import wiki.creeper.harbor.jobs.content.JobsContent;
import wiki.creeper.harbor.jobs.service.CapstoneService;
import wiki.creeper.harbor.jobs.service.CatchUpService;
import wiki.creeper.harbor.jobs.service.JobsManager;
import wiki.creeper.harbor.jobs.ui.JobCommands;
import wiki.creeper.harbor.jobs.ui.SkillMenu;

/**
 * Harbor-Jobs (docs 20 2장): 직업 단계·계열·레벨·경험치·스킬 트리·물비늘 사용. 결과는 Core 보정자로 올리고, 다른 모듈은
 * ServicesManager의 {@link JobsService}만 쓴다.
 */
public final class HarborJobsPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "jobs";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        ctx.migrate();
        JobsContent.TYPES.forEach(ctx::contentType);
        ctx.contentCheck(Balance.TYPE, JobsContent.CURVE_CHECK);
        ctx.contentCheck(JobLineDef.TYPE, JobsContent.CAPSTONE_CHECK);
        ctx.playerComponent(JobsManager.COMPONENT);

        JobsManager jobs = new JobsManager(this, api, ctx.tasks(), getSLF4JLogger());
        CapstoneService capstones = new CapstoneService(api, jobs, ctx.tasks());
        CatchUpService catchUp = new CatchUpService(api, ctx.tasks(), getSLF4JLogger());
        jobs.attach(capstones, catchUp);
        ctx.listen(jobs);
        ctx.listen(capstones);
        ctx.listen(catchUp);
        capstones.start();
        catchUp.start();

        JobCommands commands =
                new JobCommands(api, jobs, new SkillMenu(api, jobs), capstones, catchUp);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        getServer()
                .getServicesManager()
                .register(JobsService.class, jobs, this, ServicePriority.Normal);
        // NPC 선택 의존: 대화 액션 job:change_line · job:reset_skill, 전직 컷씬 (NPC가 없으면 클래스를 불러오지 않는다)
        var npc = getServer().getPluginManager().getPlugin("Harbor-Npc");
        if (npc != null && npc.isEnabled()) {
            wiki.creeper.harbor.jobs.npc.JobsNpcHooks.register(this, api, jobs);
        }
    }

    @Override
    protected void disable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}
