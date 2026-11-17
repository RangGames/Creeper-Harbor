package wiki.creeper.harbor.proxy;

import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** harbor-proxy.yml messages 섹션의 문구 (MiniMessage). */
final class ProxyText {

    private final Map<String, String> messages;

    ProxyText(Map<String, String> messages) {
        this.messages = messages;
    }

    Component get(String key, TagResolver... resolvers) {
        String raw = messages.get(key);
        return raw == null
                ? Component.text(key)
                : MiniMessage.miniMessage().deserialize(raw, resolvers);
    }
}
