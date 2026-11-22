package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.proxy.ProxyServer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

/**
 * Text from the messages section of harbor-proxy.yml (MiniMessage). Keys missing from the server
 * config file use the default harbor-proxy.yml values in the jar (so new text keys on existing
 * installs do not show up as raw key names).
 */
final class ProxyText {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final Map<String, String> messages;
    private final Map<String, String> defaults;

    ProxyText(Map<String, String> messages) {
        this(messages, Map.of());
    }

    ProxyText(Map<String, String> messages, Map<String, String> defaults) {
        this.messages = messages;
        this.defaults = defaults;
    }

    Component get(String key, TagResolver... resolvers) {
        String raw = lookup(key);
        return raw == null
                ? Component.text(key)
                : MiniMessage.miniMessage().deserialize(raw, resolvers);
    }

    /**
     * Raw text without tag parsing (for short words used as placeholder values). The key if
     * missing.
     */
    String raw(String key) {
        String raw = lookup(key);
        return raw == null ? key : raw;
    }

    /** Time display (server time zone). null gives the common.permanent text. */
    String time(@Nullable Instant instant) {
        return instant == null ? raw("common.permanent") : TIME.format(instant);
    }

    /** Sends to online staff with the permission (multi-account alerts, heartbeat loss). */
    void notifyStaff(ProxyServer proxy, String permission, Component message) {
        proxy.getAllPlayers().stream()
                .filter(p -> p.hasPermission(permission))
                .forEach(p -> p.sendMessage(message));
        proxy.getConsoleCommandSource().sendMessage(message);
    }

    private @Nullable String lookup(String key) {
        String raw = messages.get(key);
        return raw != null ? raw : defaults.get(key);
    }
}
