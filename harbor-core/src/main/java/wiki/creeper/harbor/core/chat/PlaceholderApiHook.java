package wiki.creeper.harbor.core.chat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * PlaceholderAPI 연동 (선택, 추가 제안). PlaceholderAPI가 켜져 있으면 문구 안의 {@code %플레이스홀더%}를 그 플레이어 값으로 바꾼다. 컴파일
 * 의존 없이 PlaceholderAPI.setPlaceholders를 찾아 부른다 (없으면 그대로 둔다).
 *
 * <p>값은 문구에 글자로 끼우지 않고 컴포넌트로 넣는다: PlaceholderAPI가 돌려주는 &amp;·§ 색 코드를 색으로 바꾸고, 값에 든 MiniMessage 태그는
 * 태그로 해석되지 않는다 (플레이어 이름 등으로 서식을 끼워 넣지 못하게). 메인 스레드에서 부른다 (확장 대부분이 스레드 안전하지 않다).
 */
public final class PlaceholderApiHook {

    private static final Pattern TOKEN = Pattern.compile("%([^%\\s]+)%");

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder()
                    .character('&')
                    .hexColors()
                    .useUnusualXRepeatedCharacterHexFormat()
                    .build();

    private final Logger log;
    private @Nullable MethodHandle setPlaceholders;
    private boolean checked;

    public PlaceholderApiHook(Logger log) {
        this.log = log;
    }

    /** 처음 쓸 때 찾는다 (PlaceholderAPI가 Core보다 늦게 켜져도 된다). 메인 스레드. */
    private @Nullable MethodHandle handle() {
        if (!checked) {
            Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
            if (papi != null && papi.isEnabled()) {
                checked = true;
                setPlaceholders = find(papi, log);
            }
        }
        return setPlaceholders;
    }

    private static @Nullable MethodHandle find(Plugin papi, Logger log) {
        try {
            Class<?> type =
                    Class.forName(
                            "me.clip.placeholderapi.PlaceholderAPI",
                            true,
                            papi.getClass().getClassLoader());
            MethodHandle handle =
                    MethodHandles.publicLookup()
                            .findStatic(
                                    type,
                                    "setPlaceholders",
                                    MethodType.methodType(
                                            String.class, OfflinePlayer.class, String.class));
            log.info("PlaceholderAPI 연동: 채팅 형식에서 %플레이스홀더%를 씁니다");
            return handle;
        } catch (ReflectiveOperationException | LinkageError e) {
            log.warn("PlaceholderAPI를 찾았지만 연동하지 못했습니다: {}", String.valueOf(e));
            return null;
        }
    }

    public boolean available() {
        return handle() != null;
    }

    /**
     * 문구의 %플레이스홀더%를 {@code <papi_0>} 같은 자리 태그로 바꾸고, 그 값을 컴포넌트로 담은 해석기를 함께 돌려준다. PlaceholderAPI가 없으면
     * 문구를 그대로 둔다.
     */
    public Applied apply(OfflinePlayer player, String template) {
        if (template.indexOf('%') < 0) {
            return new Applied(template, TagResolver.empty());
        }
        MethodHandle handle = handle();
        if (handle == null) {
            return new Applied(template, TagResolver.empty());
        }
        Matcher m = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder();
        List<TagResolver> values = new ArrayList<>();
        while (m.find()) {
            String tag = "papi_" + values.size();
            values.add(Placeholder.component(tag, value(handle, player, m.group())));
            m.appendReplacement(out, Matcher.quoteReplacement("<" + tag + ">"));
        }
        m.appendTail(out);
        return new Applied(out.toString(), TagResolver.resolver(values));
    }

    private static Component value(MethodHandle handle, OfflinePlayer player, String token) {
        try {
            String raw = (String) handle.invokeExact(player, token);
            return LEGACY.deserialize(raw.replace('§', '&'));
        } catch (Throwable e) {
            return Component.text(token);
        }
    }

    /**
     * @param template 자리 태그로 바꾼 문구
     * @param resolver 자리 태그 값
     */
    public record Applied(String template, TagResolver resolver) {}
}
