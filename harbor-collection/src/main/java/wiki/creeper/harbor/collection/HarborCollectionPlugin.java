package wiki.creeper.harbor.collection;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Objects;
import org.bukkit.plugin.ServicePriority;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.collection.api.CodexService;
import wiki.creeper.harbor.collection.api.HintService;
import wiki.creeper.harbor.collection.api.RecordService;
import wiki.creeper.harbor.collection.content.CollectionContent;
import wiki.creeper.harbor.collection.service.AquariumManager;
import wiki.creeper.harbor.collection.service.CollectionCommands;
import wiki.creeper.harbor.collection.service.CollectionManager;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.fishing.api.FishingService;

/** Harbor-Collection (docs 20 4장): 도감·힌트·최대어 기록·항구 수족관. 포획 판정은 하지 않고 Fishing의 포획 이벤트를 받아 기록만 한다. */
public final class HarborCollectionPlugin extends HarborModule {

    @Override
    public String moduleId() {
        return "collection";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        ctx.migrate();
        CollectionContent.TYPES.forEach(ctx::contentType);
        ctx.playerComponent(CollectionManager.COMPONENT);

        CollectionManager codex = new CollectionManager(api, ctx.tasks(), getSLF4JLogger());
        AquariumManager aquarium = new AquariumManager(api, ctx.tasks(), getSLF4JLogger());
        ctx.listen(codex);
        codex.start();
        aquarium.start();
        Objects.requireNonNull(
                        getServer().getServicesManager().load(FishingService.class),
                        "Harbor-Fishing이 켜져 있어야 합니다")
                .registerCodex(codex);

        CollectionCommands commands = new CollectionCommands(api, codex, aquarium);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        var services = getServer().getServicesManager();
        services.register(CodexService.class, codex, this, ServicePriority.Normal);
        services.register(HintService.class, codex, this, ServicePriority.Normal);
        services.register(RecordService.class, codex, this, ServicePriority.Normal);
        services.register(AquariumService.class, aquarium, this, ServicePriority.Normal);
    }

    @Override
    protected void disable() {
        getServer().getServicesManager().unregisterAll(this);
    }
}
