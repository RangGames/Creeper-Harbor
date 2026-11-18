package wiki.creeper.harbor.wildlife.runtime;

import java.util.random.RandomGenerator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.wildlife.api.WildlifeService;
import wiki.creeper.harbor.wildlife.sim.ProfileIndex;
import wiki.creeper.harbor.wildlife.sim.SimWorld;

/** 실행 부품 조립 (모듈 메인 클래스가 만든다). */
public final class WildlifeRuntime implements Listener {

    private final WildlifeKit kit;
    private final SimEngine engine;
    private final WildlifeManager manager;
    private final MediumCache media;
    private final DebugOverlay debug;

    public WildlifeRuntime(ModuleContext ctx, WildlifeKit kit) {
        this.kit = kit;
        kit.apply(ctx.api().content().snapshot(), kit.build(ctx.api().content().snapshot()));
        SimWorld world =
                new SimWorld(
                        kit.grid::field, kit.balance(), RandomGenerator.of("L64X128MixRandom"));
        this.engine = new SimEngine(world, kit.log, kit.balance().simPeriodMillis());
        this.manager = new WildlifeManager(kit, engine);
        this.media =
                new MediumCache(
                        kit,
                        (w, cx, cz) -> {
                            String name = w.getName();
                            engine.submit(sim -> sim.removeChunk(name, cx, cz));
                        });
        this.debug = new DebugOverlay(kit, manager);
        manager.debug(debug);
        FishingLink fishing = new FishingLink(kit, manager);
        ctx.listen(this);
        ctx.listen(manager);
        ctx.listen(media);
        ctx.listen(fishing);
        ctx.listen(new WorldLink(manager));
        ctx.adminCommand(new WildCommands(kit, manager, media, fishing, debug).adminCommand());
        // 모듈 형식이 들어간 콘텐츠는 enable 뒤 다시 읽히므로 다음 틱에 한 번 더 만든다
        var _ =
                ctx.tasks()
                        .sync(
                                () -> {
                                    ContentSnapshot snap = kit.api.content().snapshot();
                                    ProfileIndex before = kit.index();
                                    kit.apply(snap, kit.build(snap));
                                    manager.reloaded(before, kit.index());
                                    media.rescan();
                                });
        engine.start();
        media.start();
        manager.start();
        debug.start();
    }

    public WildlifeService service() {
        return manager;
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        ContentSnapshot snap = event.snapshot();
        var _ =
                kit.tasks
                        .supplyAsync(() -> kit.build(snap))
                        .thenAcceptAsync(
                                next -> {
                                    ProfileIndex before = kit.index();
                                    kit.apply(snap, next);
                                    manager.reloaded(before, next);
                                    media.rescan();
                                },
                                kit.tasks.main());
    }

    public void shutdown() {
        engine.stop();
        debug.shutdown();
        manager.shutdown();
    }
}
