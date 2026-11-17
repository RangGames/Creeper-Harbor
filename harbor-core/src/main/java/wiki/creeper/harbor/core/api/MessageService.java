package wiki.creeper.harbor.core.api;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** messages_ko.yml의 키로 문구를 만든다 (MiniMessage). 코드에는 문구를 직접 쓰지 않는다. 스냅샷을 읽기만 하므로 어느 스레드에서 써도 된다. */
public interface MessageService {

    /** 키가 없으면 키 이름을 그대로 보여주고 경고 로그를 한 번 남긴다. */
    Component get(String key, TagResolver... resolvers);

    void send(Audience audience, String key, TagResolver... resolvers);

    boolean has(String key);
}
