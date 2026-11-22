package wiki.creeper.harbor.quest;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.ServicePriority;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.quest.api.ChronicleService;
import wiki.creeper.harbor.quest.api.ObjectiveSignal;
import wiki.creeper.harbor.quest.api.ObjectiveTypeRegistry;
import wiki.creeper.harbor.quest.api.QuestService;
import wiki.creeper.harbor.quest.api.RegionProgressProvider;
import wiki.creeper.harbor.quest.api.ServerProgressService;
import wiki.creeper.harbor.quest.bridge.WorldRegionProgress;
import wiki.creeper.harbor.quest.content.ChronicleDef;
import wiki.creeper.harbor.quest.content.QuestContent;
import wiki.creeper.harbor.quest.content.QuestDef;
import wiki.creeper.harbor.quest.service.NpcLink;
import wiki.creeper.harbor.quest.service.QuestEngine;
import wiki.creeper.harbor.quest.service.QuestListener;
import wiki.creeper.harbor.quest.service.QuestServices;
import wiki.creeper.harbor.quest.ui.QuestBook;
import wiki.creeper.harbor.quest.ui.QuestCommands;
import wiki.creeper.harbor.quest.ui.QuestDialogs;

/**
 * Harbor-Quest (docs 20 ch. 6): an engine that runs YAML quests as a state machine (requirements →
 * objectives → rewards), the fishermen's guild board (daily and weekly requests), collection
 * requests, the "Harbor Chronicle" with chronicle recaps (D9), and the climax unlock check. NPC
 * entities, dialog screens and cutscenes belong to NPC; job advancement is run by Jobs.
 */
public final class HarborQuestPlugin extends HarborModule {

    private @Nullable QuestServices services;

    @Override
    public String moduleId() {
        return "quest";
    }

    @Override
    protected void enable(ModuleContext ctx) {
        HarborApi api = ctx.api();
        ctx.migrate();
        QuestContent.TYPES.forEach(ctx::contentType);
        ctx.playerComponent(QuestEngine.COMPONENT);

        QuestServices s = QuestServices.create(this, api, ctx.tasks(), getSLF4JLogger());
        services = s;
        QuestEngine engine = s.engine();
        ctx.contentCheck(QuestDef.TYPE, QuestContent.QUEST_CHECK);
        // Objective types: the built-in types + types other modules registered through
        // ObjectiveTypeRegistry (each check looks at the current list)
        ctx.contentCheck(
                QuestDef.TYPE,
                (quest, check) -> {
                    QuestContent.objectiveTypes(engine.types()).check(quest, check);
                    engine.validateCustom(quest, check::error);
                });
        ctx.contentCheck(ChronicleDef.TYPE, QuestContent.CHRONICLE_CHECK);

        QuestDialogs dialogs = new QuestDialogs(api, s);
        new NpcLink(s, dialogs).register(this);
        ctx.listen(new QuestListener(s));
        ctx.listen(s.board());
        ctx.handoffBlocker(s::handoff);
        s.registerHooks(this);
        s.start(dialogs::boardRoot);

        QuestCommands commands =
                new QuestCommands(
                        api,
                        s,
                        dialogs,
                        new QuestBook(api, s, dialogs),
                        new wiki.creeper.harbor.quest.editor.EditorStore(
                                api.content(), ctx.tasks()));
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        var registry = getServer().getServicesManager();
        registry.register(QuestService.class, engine, this, ServicePriority.Normal);
        registry.register(ObjectiveSignal.class, engine, this, ServicePriority.Normal);
        registry.register(ObjectiveTypeRegistry.class, engine, this, ServicePriority.Normal);
        // The default implementation has the lowest priority: if World registers with a higher
        // priority, its implementation is used
        registry.register(RegionProgressProvider.class, engine, this, ServicePriority.Lowest);
        if (getServer().getPluginManager().isPluginEnabled("Harbor-World")) {
            var _ = WorldRegionProgress.register(this);
        }
        registry.register(ChronicleService.class, s.chronicle(), this, ServicePriority.Normal);
        registry.register(
                ServerProgressService.class, s.serverProgress(), this, ServicePriority.Normal);
    }

    @Override
    protected void disable() {
        QuestServices s = services;
        if (s != null) {
            s.stop();
        }
        getServer().getServicesManager().unregisterAll(this);
    }
}
