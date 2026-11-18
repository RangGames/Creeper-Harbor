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
import wiki.creeper.harbor.quest.ui.QuestCommands;
import wiki.creeper.harbor.quest.ui.QuestDialogs;

/**
 * Harbor-Quest (docs 20 6장): YAML 퀘스트를 상태 기계(조건 → 목표 → 보상)로 돌리는 엔진, 어부 길드 게시판(일일·주간 의뢰), 수집 의뢰, 「항구
 * 연대기」와 연대기 회상(D9), 클라이맥스 개방 판정. NPC 엔티티·대화 화면·컷씬은 NPC, 전직 실행은 Jobs가 맡는다.
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
        // 목표 유형: 기본 유형 + 다른 모듈이 ObjectiveTypeRegistry로 등록한 유형 (검사 때마다 지금 목록을 본다)
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

        QuestCommands commands = new QuestCommands(api, s, dialogs);
        ctx.adminCommand(commands.adminCommand());
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> commands.registerPlayerCommands(event.registrar()));

        var registry = getServer().getServicesManager();
        registry.register(QuestService.class, engine, this, ServicePriority.Normal);
        registry.register(ObjectiveSignal.class, engine, this, ServicePriority.Normal);
        registry.register(ObjectiveTypeRegistry.class, engine, this, ServicePriority.Normal);
        // 기본 구현은 가장 낮은 우선순위: World가 더 높은 우선순위로 등록하면 그 구현을 쓴다
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
