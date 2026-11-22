package wiki.creeper.harbor.season;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Set;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.season.api.HallOfFameService;
import wiki.creeper.harbor.season.api.RankingService;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.SeasonHookRegistry;
import wiki.creeper.harbor.season.api.TitleService;
import wiki.creeper.harbor.season.api.WeeklyLimitService;
import wiki.creeper.harbor.season.clock.SeasonClockManager;
import wiki.creeper.harbor.season.command.SeasonCommands;
import wiki.creeper.harbor.season.content.ResetPlan;
import wiki.creeper.harbor.season.content.SeasonContent;
import wiki.creeper.harbor.season.data.SeasonProfile;
import wiki.creeper.harbor.season.data.SeasonRepository;
import wiki.creeper.harbor.season.gate.SeasonGate;
import wiki.creeper.harbor.season.hof.HallOfFameManager;
import wiki.creeper.harbor.season.limit.WeeklyLimitManager;
import wiki.creeper.harbor.season.ranking.RankingManager;
import wiki.creeper.harbor.season.service.HookRegistry;
import wiki.creeper.harbor.season.service.TransitionCoordinator;
import wiki.creeper.harbor.season.title.TitleManager;
import wiki.creeper.harbor.season.transition.PlanCoverage;
import wiki.creeper.harbor.season.transition.SchemaInspector;
import wiki.creeper.harbor.season.transition.TransitionEngine;
import wiki.creeper.harbor.season.ui.HallOfFameMenu;
import wiki.creeper.harbor.season.ui.RankingMenu;
import wiki.creeper.harbor.season.ui.TitleMenu;

/**
 * Harbor-Season (docs 20 ch. 11): season clock, weekly limits, titles, rankings, hall of fame and
 * season transition. Its only code dependency is Core; it connects to other modules solely through
 * season/reset_plan.yml (data) and the api package (SeasonHook, HallOfFameService.submit ...). It
 * does not subscribe to other modules' events.
 */
public final class HarborSeasonPlugin extends HarborModule {

    /**
     * Cache of tables with a season column (for the reset_plan reload check, filled asynchronously
     * after startup).
     */
    private volatile @Nullable Set<String> seasonTables;

    @Override
    public String moduleId() {
        return "season";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        Tasks tasks = ctx.tasks();
        Logger log = getSLF4JLogger();
        ctx.migrate();
        SeasonContent.TYPES.forEach(ctx::contentType);
        ctx.contentCheck(
                ResetPlan.TYPE,
                (plan, check) -> {
                    Set<String> tables = seasonTables;
                    if (tables != null) {
                        for (String t : PlanCoverage.missing(plan, tables)) {
                            check.error("season 열이 있는 테이블 " + t + "이(가) 계획에 없습니다");
                        }
                    }
                });

        SeasonContentView content = new SeasonContentView(api);
        SeasonClockManager clock = new SeasonClockManager(api, content, tasks, log);
        PlayerComponent<SeasonProfile> component =
                new PlayerComponent<>(
                        "season",
                        (c, uuid, season) ->
                                SeasonRepository.loadProfile(
                                        c, uuid, season.value(), clock.startsAt().toInstant()),
                        (c, uuid, season, value) -> SeasonRepository.saveProfile(c, uuid, value));
        ctx.playerComponent(component);

        WeeklyLimitManager limits = new WeeklyLimitManager(api.database(), clock, tasks);
        TitleManager titles = new TitleManager(api, content, clock, component, tasks, log);
        RankingManager rankings = new RankingManager(api, content, clock, tasks, log);
        HallOfFameManager hof = new HallOfFameManager(api, content, clock, tasks, log);
        HookRegistry hooks = new HookRegistry();
        TransitionCoordinator coordinator =
                new TransitionCoordinator(
                        api, this, content, clock, rankings, hof, hooks, tasks, log);
        TransitionEngine engine =
                new TransitionEngine(api.database(), api.locks(), coordinator, content::plan);

        ctx.listen(new SeasonGate(api, content, clock, component, log));
        ctx.listen(coordinator);
        clock.onWeeklyReset(rankings::weeklySnapshot);
        rankings.onLeaderTick(hof::refreshDerived);

        ServicesManager services = getServer().getServicesManager();
        services.register(SeasonClock.class, clock, this, ServicePriority.Normal);
        services.register(WeeklyLimitService.class, limits, this, ServicePriority.Normal);
        services.register(TitleService.class, titles, this, ServicePriority.Normal);
        services.register(RankingService.class, rankings, this, ServicePriority.Normal);
        services.register(HallOfFameService.class, hof, this, ServicePriority.Normal);
        services.register(SeasonHookRegistry.class, hooks, this, ServicePriority.Normal);

        SeasonCommands commands =
                new SeasonCommands(
                        api,
                        content,
                        clock,
                        rankings,
                        titles,
                        limits,
                        engine,
                        new RankingMenu(api, rankings),
                        new HallOfFameMenu(api, hof, tasks),
                        new TitleMenu(api, content, titles),
                        tasks);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        // Content is read after module enable finishes, so startup work that needs content runs on
        // the tick after the server is fully up
        var _ =
                tasks.sync(
                        () -> {
                            clock.start();
                            titles.start();
                            rankings.start();
                            coordinator.start();
                            checkPlanCoverage(api, content, log);
                        });
    }

    /**
     * Checks that reset_plan covers every table with a season column in the DB (after other
     * modules' migrations finish).
     */
    private void checkPlanCoverage(HarborApi api, SeasonContentView content, Logger log) {
        var _ =
                api.database()
                        .async(SchemaInspector::seasonTables)
                        .thenAccept(
                                tables -> {
                                    seasonTables = Set.copyOf(tables);
                                    List<String> missing =
                                            PlanCoverage.missing(content.plan(), tables);
                                    if (!missing.isEmpty()) {
                                        log.error(
                                                "season/reset_plan.yml에 없는 시즌 테이블 {}개: {} (시즌 전환 PRECHECK가 실패합니다)",
                                                missing.size(),
                                                missing);
                                    }
                                })
                        .exceptionally(
                                e -> {
                                    log.warn(
                                            "시즌 테이블 목록을 읽지 못했습니다: {}",
                                            String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    @Override
    protected void disable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}
