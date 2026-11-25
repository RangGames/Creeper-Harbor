package wiki.creeper.harbor.core.chat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * PlaceholderAPI integration (optional, proposed). When PlaceholderAPI is enabled, {@code
 * %placeholder%} tokens in text are replaced with that player's values. It looks up and calls
 * PlaceholderAPI.setPlaceholders without a compile dependency (text is left as is if absent).
 *
 * <p>Values are inserted as components rather than spliced into the text: &amp;/§ color codes
 * returned by PlaceholderAPI become colors, and MiniMessage tags in values are not parsed as tags
 * (so formatting cannot be injected via player names etc.). Call on the main thread (most
 * expansions are not thread-safe).
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

    /** Looked up on first use (PlaceholderAPI may be enabled after Core). Main thread. */
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
     * Replaces %placeholders% in the text with slot tags like {@code <papi_0>} and returns them
     * together with a resolver holding the values as components. Without PlaceholderAPI the text is
     * left unchanged.
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
            if (token.startsWith("%harbor_head")) {
                // Harbor's own head placeholders are MiniMessage (font, hex colors) built from skin
                // pixels only, never from player input
                return MiniMessage.miniMessage().deserialize(raw);
            }
            return LEGACY.deserialize(raw.replace('§', '&'));
        } catch (Throwable e) {
            return Component.text(token);
        }
    }

    /**
     * @param template the text with slot tags substituted
     * @param resolver the slot tag values
     */
    public record Applied(String template, TagResolver resolver) {}
}
