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
 * 명령의 어종 인자 (추가 제안). 플레이어는 한국어 이름("항구 고등어", 공백 포함)이나 도감 번호로 적는다. 공백이 들어가므로 명령의 마지막 인자로만 쓴다
 * (greedy). 관리 명령을 위해 어종 ID(harbor_mackerel, fish/…, harbor:fish/…)와 모델 이름도 받는다.
 */
public final class FishArgs {

    private FishArgs() {}

    /** 마지막 인자로 붙일 어종 인자. Tab 목록은 한국어 이름 (설명에 도감 번호). */
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

    /** 이름(공백 무시) · 도감 번호(1, 001) · 어종 ID · 모델 이름 순으로 찾는다. */
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

    /** 비교용: 공백·작은따옴표를 뺀다 ("늪지의 주인 '그놈'" = "늪지의주인그놈"). */
    private static String compact(String s) {
        return s.replace(" ", "").replace("'", "").strip();
    }

    private static String no(FishDef f) {
        return String.format(Locale.ROOT, "%03d", f.no());
    }
}
