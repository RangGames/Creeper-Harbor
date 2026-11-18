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
 * harbor-proxy.yml messages 섹션의 문구 (MiniMessage). 서버 설정 파일에 없는 키는 jar에 든 기본 harbor-proxy.yml 값을 쓴다
 * (기존 설치에 새 문구 키가 생겨도 키 이름이 그대로 보이지 않게).
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

    /** 태그를 해석하지 않은 원문 (자리표시자 값으로 넣을 짧은 낱말용). 없으면 키. */
    String raw(String key) {
        String raw = lookup(key);
        return raw == null ? key : raw;
    }

    /** 시각 표시 (서버 시간대). null이면 common.permanent 문구. */
    String time(@Nullable Instant instant) {
        return instant == null ? raw("common.permanent") : TIME.format(instant);
    }

    /** 권한이 있는 접속 운영진에게 보낸다 (다계정 경보, 하트비트 끊김). */
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
