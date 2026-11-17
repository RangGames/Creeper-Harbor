package wiki.creeper.harbor.core;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.db.Migrations;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborModule;
import wiki.creeper.harbor.core.api.ModuleContext;
import wiki.creeper.harbor.core.api.ModuleHost;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.command.HarborCommand;
import wiki.creeper.harbor.core.content.HarborContent;
import wiki.creeper.harbor.core.player.PlayerManager;
import wiki.creeper.harbor.core.task.Tasks;

/** 게임 모듈 연결 ('16. 플러그인 설계' 5.1 모듈 연결 단계). */
final class ModuleRegistry implements ModuleHost {

    private final HarborApi api;
    private final HarborContent content;
    private final PlayerManager players;
    private final HarborCommand command;
    private final Logger log;
    private final Map<String, HarborModule> modules = new ConcurrentHashMap<>();

    ModuleRegistry(HarborApi api, HarborContent content, PlayerManager players, HarborCommand command, Logger log) {
        this.api = api;
        this.content = content;
        this.players = players;
        this.command = command;
        this.log = log;
    }

    @Override
    public Handle attach(HarborModule module) {
        HarborModule previous = modules.putIfAbsent(module.moduleId(), module);
        if (previous != null) {
            throw new IllegalStateException("모듈 ID가 겹칩니다: " + module.moduleId());
        }
        return new ModuleHandle(module);
    }

    private final class ModuleHandle implements Handle, ModuleContext {

        private final HarborModule module;
        private final Tasks tasks;
        private final List<ContentType<?>> addedTypes = new ArrayList<>();

        ModuleHandle(HarborModule module) {
            this.module = module;
            this.tasks = new Tasks(module);
        }

        @Override
        public ModuleContext context() {
            return this;
        }

        @Override
        public void finishEnable() {
            if (addedTypes.isEmpty()) {
                return;
            }
            // 새 형식이 들어왔으니 전체를 다시 읽는다. 실패하면 서버 시작 중단 (반쪽 콘텐츠로 열지 않음)
            Result<ContentSnapshot, ValidationErrors> result = content.loadBlocking();
            if (result instanceof Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors)) {
                log.error("모듈 {} 콘텐츠 오류 {}건:\n{}", module.moduleId(), errors.size(), errors.format());
                Bukkit.shutdown();
            }
        }

        @Override
        public void failEnable(RuntimeException cause) {
            log.error("모듈 {}을(를) 켜지 못해 서버를 멈춥니다", module.moduleId(), cause);
            Bukkit.shutdown();
        }

        @Override
        public void detach() {
            tasks.cancelAll();
            modules.remove(module.moduleId(), module);
        }

        // ---------------------------------------------------------------- ModuleContext

        @Override
        public HarborModule plugin() {
            return module;
        }

        @Override
        public HarborApi api() {
            return api;
        }

        @Override
        public Tasks tasks() {
            return tasks;
        }

        @Override
        public void contentType(ContentType<?> type) {
            content.register(type);
            addedTypes.add(type);
        }

        @Override
        public <T> void contentCheck(ContentType<T> type, ContentCheck<T> check) {
            content.addCheck(type, check);
        }

        @Override
        public void playerComponent(PlayerComponent<?> component) {
            players.register(component);
        }

        @Override
        public void adminCommand(LiteralArgumentBuilder<CommandSourceStack> builder) {
            command.addModuleCommand(builder);
        }

        @Override
        public void migrate() {
            int applied = Migrations.migrate(api.database().dataSource(), module.moduleId(), module.getClass().getClassLoader());
            if (applied > 0) {
                log.info("모듈 {} 마이그레이션 {}건 적용", module.moduleId(), applied);
            }
        }

        @Override
        public void listen(Listener listener) {
            Bukkit.getPluginManager().registerEvents(listener, module);
        }
    }
}
