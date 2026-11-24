package wiki.creeper.harbor.core;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.tags.DialogTagKeys;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import wiki.creeper.harbor.common.content.type.QuickMenu;
import wiki.creeper.harbor.core.content.DefaultContent;

/**
 * Datapack registry entry registration stage, used instead of a datapack. Registers the quick
 * actions menu (content/quick_menu.yml, "4. Fishing presentation" 1.3) as the Dialog {@code
 * harbor:quick_menu} and adds it to the {@code minecraft:quick_actions} tag, so the client quick
 * actions key (default G) opens it. Fishing-rod enchantments will be registered here once Phase 1
 * technical validation confirms the Registry API works.
 */
public final class HarborCoreBootstrap implements PluginBootstrap {

    /** Quick actions menu Dialog key. */
    public static final Key QUICK_MENU = Key.key("harbor", "quick_menu");

    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLogger().info("Harbor-Core 부트스트랩");
        Optional<QuickMenu> menu = loadMenu(context).filter(m -> !m.buttons().isEmpty());
        if (menu.isEmpty()) {
            context.getLogger().warn("quick_menu.yml을 읽지 못해 빠른 메뉴(G 키)를 등록하지 않습니다");
            return;
        }
        TypedKey<io.papermc.paper.dialog.Dialog> key =
                TypedKey.create(RegistryKey.DIALOG, QUICK_MENU);
        QuickMenu m = menu.get();
        context.getLifecycleManager()
                .registerEventHandler(
                        RegistryEvents.DIALOG.compose(),
                        event ->
                                event.registry()
                                        .register(
                                                key,
                                                b ->
                                                        b.base(
                                                                        DialogBase.builder(
                                                                                        text(
                                                                                                m
                                                                                                        .title()))
                                                                                .canCloseWithEscape(
                                                                                        true)
                                                                                .pause(false)
                                                                                .afterAction(
                                                                                        DialogBase
                                                                                                .DialogAfterAction
                                                                                                .CLOSE)
                                                                                .build())
                                                                .type(
                                                                        DialogType.multiAction(
                                                                                        buttons(m))
                                                                                .columns(
                                                                                        m.columns())
                                                                                .build())));
        context.getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.TAGS.postFlatten(RegistryKey.DIALOG),
                        event ->
                                event.registrar()
                                        .addToTag(DialogTagKeys.QUICK_ACTIONS, List.of(key)));
    }

    /**
     * The server's plugins/Harbor/content/quick_menu.yml (operators may have edited it), otherwise
     * the bundled default. Content sync runs later in onEnable, so a first start uses the default.
     */
    private static Optional<QuickMenu> loadMenu(BootstrapContext context) {
        Path data = context.getDataDirectory().toAbsolutePath();
        Path plugins = data.getParent();
        Path file =
                (plugins == null ? data : plugins)
                        .resolve("Harbor")
                        .resolve("content")
                        .resolve(QuickMenu.FILE);
        try (InputStream bundled =
                HarborCoreBootstrap.class
                        .getClassLoader()
                        .getResourceAsStream(DefaultContent.PREFIX + QuickMenu.FILE)) {
            return QuickMenu.load(file, bundled);
        } catch (IOException | RuntimeException e) {
            context.getLogger().warn("quick_menu.yml 읽기 실패: {}", String.valueOf(e));
            return Optional.empty();
        }
    }

    private static List<ActionButton> buttons(QuickMenu menu) {
        List<ActionButton> result = new ArrayList<>();
        for (QuickMenu.Button b : menu.buttons()) {
            ActionButton.Builder button =
                    ActionButton.builder(text(b.label()))
                            .width(b.width())
                            .action(
                                    DialogAction.staticAction(
                                            ClickEvent.runCommand("/" + b.command())));
            if (!b.tooltip().isEmpty()) {
                button.tooltip(text(b.tooltip()));
            }
            result.add(button.build());
        }
        return result;
    }

    private static Component text(String miniMessage) {
        return MiniMessage.miniMessage().deserialize(miniMessage);
    }
}
