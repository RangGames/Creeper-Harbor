package wiki.creeper.harbor.skript.bridge;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.skript.ScriptArgs;

/** Helpers shared by the Core, Jobs and Season bridges (Core api only). */
final class Shared {

    private Shared() {}

    static HarborApi api() {
        return Harbor.api();
    }

    /** Reference ID for a script-made change (ledger, mail, titles). */
    static String ref() {
        return "skript:" + UUID.randomUUID();
    }

    /** {@code (0:gold|1:mulbineul)} parse mark → currency. */
    static Currency currency(int mark) {
        return mark == 1 ? Currency.MULBINEUL : Currency.GOLD;
    }

    static OfflinePlayer offline(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }

    static Component mini(String text) {
        return text.isEmpty() ? Component.empty() : MiniMessage.miniMessage().deserialize(text);
    }

    static List<Component> minis(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String line : lines) {
            out.add(mini(line));
        }
        return out;
    }

    /** Component → Skript text (section color codes). */
    static String legacy(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    /**
     * {@code key=value} placeholders. Unparsed values are inserted as plain text; parsed values are
     * read as MiniMessage.
     */
    static TagResolver[] resolvers(List<String> pairs, boolean parsed) {
        List<TagResolver> out = new ArrayList<>();
        for (Map.Entry<String, String> e : ScriptArgs.pairs(pairs).entrySet()) {
            out.add(
                    parsed
                            ? Placeholder.parsed(e.getKey(), e.getValue())
                            : Placeholder.unparsed(e.getKey(), e.getValue()));
        }
        return out.toArray(new TagResolver[0]);
    }

    static ch.njol.skript.util.Date date(Instant instant) {
        return new ch.njol.skript.util.Date(instant.toEpochMilli());
    }

    static ch.njol.skript.util.Date date(ZonedDateTime time) {
        return date(time.toInstant());
    }

    /** Waits for all futures and returns their results in order (on the completing thread). */
    static <T> CompletableFuture<List<T>> all(List<CompletableFuture<T>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }
}
