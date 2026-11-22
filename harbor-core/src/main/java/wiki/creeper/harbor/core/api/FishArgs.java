package wiki.creeper.harbor.core.api;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.rig.FishRigs;

/**
 * Fish species command argument (proposed). Players type the Korean name ("항구 고등어", spaces allowed)
 * or the collection number. Because it can contain spaces, use it only as the last (greedy)
 * argument of a command. For admin commands it also accepts the species ID (harbor_mackerel,
 * fish/…, harbor:fish/…) and the model name.
 */
public final class FishArgs {

    private FishArgs() {}

    /**
     * Species argument to attach as the last argument. Tab completion lists Korean names
     * (collection number in the tooltip).
     */
    public static RequiredArgumentBuilder<CommandSourceStack, String> argument(
            String name, Supplier<ContentSnapshot> content) {
        return Commands.argument(name, StringArgumentType.greedyString())
                .suggests(
                        (ctx, b) -> {
                            String typed = compact(b.getRemaining());
                            content.get().all(FishDef.TYPE).values().stream()
                                    .sorted(Comparator.comparingInt(FishDef::no))
                                    .filter(
                                            f ->
                                                    typed.isEmpty()
                                                            || compact(f.name()).contains(typed)
                                                            || no(f).startsWith(typed))
                                    .forEach(
                                            f ->
                                                    b.suggest(
                                                            f.name(),
                                                            MessageComponentSerializer.message()
                                                                    .serialize(
                                                                            Component.text(
                                                                                    "No."
                                                                                            + no(
                                                                                                    f)))));
                            return b.buildFuture();
                        });
    }

    public static Optional<FishDef> get(
            CommandContext<CommandSourceStack> ctx, String name, ContentSnapshot snapshot) {
        return resolve(snapshot, StringArgumentType.getString(ctx, name));
    }

    /**
     * Looks up by name (ignoring spaces), collection number (1, 001), species ID, then model name.
     */
    public static Optional<FishDef> resolve(ContentSnapshot snapshot, String raw) {
        String key = compact(raw);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        var all = snapshot.all(FishDef.TYPE).values();
        for (FishDef f : all) {
            if (compact(f.name()).equals(key)) {
                return Optional.of(f);
            }
        }
        if (key.length() <= 4 && key.chars().allMatch(c -> c >= '0' && c <= '9')) {
            int no = Integer.parseInt(key);
            return all.stream().filter(f -> f.no() == no).findFirst();
        }
        String path = key.toLowerCase(Locale.ROOT);
        String prefix = HarborId.DEFAULT_NAMESPACE + ":";
        if (path.startsWith(prefix)) {
            path = path.substring(prefix.length());
        }
        if (path.startsWith("fish/")) {
            path = path.substring("fish/".length());
        }
        for (FishDef f : all) {
            if (f.id().id().name().equals(path) || FishRigs.rigOf(f).id().name().equals(path)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /** For comparison: strips spaces and single quotes ("늪지의 주인 '그놈'" = "늪지의주인그놈"). */
    private static String compact(String s) {
        return s.replace(" ", "").replace("'", "").strip();
    }

    private static String no(FishDef f) {
        return String.format(Locale.ROOT, "%03d", f.no());
    }
}
