package wiki.creeper.harbor.collection;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.Objects;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.collection.api.CollectionService;
import wiki.creeper.harbor.collection.api.DisplayCaseService;
import wiki.creeper.harbor.collection.api.HintService;
import wiki.creeper.harbor.collection.api.RecordService;
import wiki.creeper.harbor.collection.api.ReleaseService;
import wiki.creeper.harbor.collection.content.CollectionContent;
import wiki.creeper.harbor.collection.display.DisplayCaseManager;
import wiki.creeper.harbor.collection.display.ShopThemes;
import wiki.creeper.harbor.collection.hook.NpcBridge;
import wiki.creeper.harbor.collection.release.ReleaseManager;
import wiki.creeper.harbor.collection.service.AquariumManager;
import wiki.creeper.harbor.collection.service.Billboard;
import wiki.creeper.harbor.collection.service.CollectionCommands;
import wiki.creeper.harbor.collection.service.CollectionManager;
import wiki.creeper.harbor.collection.service.ItemReturns;
import wiki.creeper.harbor.collection.service.SeasonLink;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.fishing.api.FishingService;

/**
 * Harbor-Collection (docs 20 ch. 4): collection, hints, biggest-catch records, harbor aquarium
 * (tank display), personal aquarium displays, release, ranking billboard. It does not judge
 * catches; it only records Fishing's catch events. Season is required (titles, hall of fame,
 * rankings); NPC, World, and Shop are optional dependencies.
 */
public final class HarborCollectionPlugin extends HarborModule {

    private @Nullable AquariumManager aquarium;
    private @Nullable Billboard billboard;
    private @Nullable DisplayCaseManager display;

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

        ItemReturns returns = new ItemReturns(api, getSLF4JLogger());
        SeasonLink season = new SeasonLink(api, getSLF4JLogger());
        season.registerHook(this);
        CollectionManager collection =
                new CollectionManager(api, ctx.tasks(), getSLF4JLogger(), season);
        AquariumManager aquarium = new AquariumManager(api, ctx.tasks(), getSLF4JLogger(), returns);
        this.aquarium = aquarium;
        ctx.listen(collection);
        ctx.listen(aquarium);
        ctx.handoffBlocker(aquarium.handoffBlocker());
        ctx.itemCustody(aquarium::held);
        collection.start();
        aquarium.start();
        var _ = ctx.tasks().timer(20L * 300, 20L * 300, season::retryAll);
        Objects.requireNonNull(
                        getServer().getServicesManager().load(FishingService.class),
                        "Harbor-Fishing이 켜져 있어야 합니다")
                .registerCollection(collection);

        // Optional NPC dependency: dialog actions aquarium:donate · collection:hint, highest
        // reputation
        // tier (aquarium slots), release reputation
        NpcBridge npc = NpcBridge.NONE;
        if (enabled("Harbor-Npc")) {
            npc =
                    wiki.creeper.harbor.collection.hook.NpcHooks.install(
                            this, api, aquarium, collection);
        }
        ReleaseManager release =
                new ReleaseManager(api, ctx.tasks(), getSLF4JLogger(), returns, npc);
        ctx.listen(release);

        // Optional World dependency: personal aquarium displays. Shop (aquarium theme ownership)
        // enables after Collection, so it is looked up on use
        ShopThemes themes = new ShopThemes(getSLF4JLogger());
        if (enabled("Harbor-World")) {
            display =
                    wiki.creeper.harbor.collection.hook.WorldHooks.install(
                            this, api, ctx.tasks(), getSLF4JLogger(), npc, themes);
        }

        Billboard board = new Billboard(api, ctx.tasks(), collection);
        board.start();
        this.billboard = board;
        ctx.listen(board);

        CollectionCommands commands =
                new CollectionCommands(api, collection, aquarium, release, () -> display);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        var services = getServer().getServicesManager();
        services.register(CollectionService.class, collection, this, ServicePriority.Normal);
        services.register(HintService.class, collection, this, ServicePriority.Normal);
        services.register(RecordService.class, collection, this, ServicePriority.Normal);
        services.register(AquariumService.class, aquarium, this, ServicePriority.Normal);
        services.register(ReleaseService.class, release, this, ServicePriority.Normal);
        DisplayCaseManager d = display;
        if (d != null) {
            services.register(DisplayCaseService.class, d, this, ServicePriority.Normal);
        }
    }

    private boolean enabled(String name) {
        Plugin plugin = getServer().getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    @Override
    protected void disable() {
        AquariumManager a = aquarium;
        if (a != null) {
            a.stop();
        }
        Billboard b = billboard;
        if (b != null) {
            b.stop();
        }
        DisplayCaseManager d = display;
        if (d != null) {
            d.stop();
        }
        getServer().getServicesManager().unregisterAll(this);
    }
}
