package wiki.creeper.harbor.core.rig;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.rig.FishRigs;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.core.api.ContentService;
import wiki.creeper.harbor.core.api.FishArgs;

/**
 * Argument resolution and suggestions for {@link FishDemoCommand} (split out of it): fish and
 * animation lookup, the fish's rig, and the fish/animation/region suggestion providers.
 */
final class FishDemoArgs {

    /** Common animations skipped when picking a fish's own animation. */
    private static final Set<String> COMMON_ANIMATIONS = Set.of("swim", "idle", "flop");

    static final String SPECIAL = "special";

    private final RigManager rigs;
    private final ContentService content;

    FishDemoArgs(RigManager rigs, ContentService content) {
        this.rigs = rigs;
        this.content = content;
    }

    /**
     * Finds a fish by number (1, 001), Korean name, fish ID or model name (Core {@link FishArgs}).
     */
    static Optional<FishDef> findFish(ContentSnapshot snap, String raw) {
        return FishArgs.resolve(snap, raw);
    }

    Optional<RigDef> rigDef(FishDef def) {
        Optional<RigDef> own = rigs.definition(FishRigs.rigOf(def));
        return own.isPresent() ? own : rigs.definition(FishRigs.byId(def));
    }

    /**
     * For special, the fish's own animation (the first one that isn't a common animation);
     * otherwise that name. Null if the rig doesn't have it.
     */
    static @Nullable String resolveAnimation(RigDef rig, String animation) {
        if (animation.equals(SPECIAL)) {
            return rig.animations().keySet().stream()
                    .filter(a -> !COMMON_ANIMATIONS.contains(a))
                    .findFirst()
                    .orElse(null);
        }
        return rig.animations().containsKey(animation) ? animation : null;
    }

    static String no(FishDef def) {
        return String.format(Locale.ROOT, "%03d", def.no());
    }

    SuggestionProvider<CommandSourceStack> fishSuggestions() {
        return (ctx, builder) -> {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            content.snapshot().all(FishDef.TYPE).values().stream()
                    .sorted(Comparator.comparingInt(FishDef::no))
                    .forEach(
                            def -> {
                                String name = def.id().id().name();
                                String no = no(def);
                                if (name.startsWith(typed)
                                        || no.startsWith(typed)
                                        || def.name().contains(typed)) {
                                    builder.suggest(
                                            name,
                                            MessageComponentSerializer.message()
                                                    .serialize(
                                                            Component.text(
                                                                    "No."
                                                                            + no
                                                                            + " "
                                                                            + def.name())));
                                }
                            });
            return builder.buildFuture();
        };
    }

    SuggestionProvider<CommandSourceStack> animationSuggestions() {
        return (ctx, builder) -> {
            ContentSnapshot snap = content.snapshot();
            findFish(snap, StringArgumentType.getString(ctx, "fish"))
                    .flatMap(this::rigDef)
                    .ifPresent(r -> r.animations().keySet().forEach(builder::suggest));
            builder.suggest(SPECIAL);
            return builder.buildFuture();
        };
    }

    RequiredArgumentBuilder<CommandSourceStack, String> regionArg() {
        return Commands.argument("region", StringArgumentType.word())
                .suggests(
                        (ctx, builder) -> {
                            for (RegionDef r : content.snapshot().all(RegionDef.TYPE).values()) {
                                builder.suggest(
                                        r.id().id().path().substring("region/".length()),
                                        MessageComponentSerializer.message()
                                                .serialize(Component.text(r.name())));
                            }
                            return builder.buildFuture();
                        });
    }
}
