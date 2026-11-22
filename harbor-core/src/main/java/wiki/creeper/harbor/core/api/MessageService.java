package wiki.creeper.harbor.core.api;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Builds text from messages_ko.yml keys (MiniMessage). Code never contains text directly. It only
 * reads the snapshot, so any thread may use it.
 */
public interface MessageService {

    /** If the key is missing, shows the key name as is and logs a warning once. */
    Component get(String key, TagResolver... resolvers);

    void send(Audience audience, String key, TagResolver... resolvers);

    boolean has(String key);
}
