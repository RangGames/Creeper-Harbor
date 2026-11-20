package wiki.creeper.harbor.skript.bridge;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.core.api.FlagService;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MailService;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * Core 문법 (늘 등록): 레벨·직업·평판 값, 시즌 번호, 플레이어·서버 플래그, 타이틀·액션바, 아이템 우편.
 *
 * <p>플래그 쓰기·우편은 Core가 비동기로 DB에 쓴다 (스크립트는 기다리지 않는다). 플래그 이름은 소문자·숫자·밑줄을 / 로 잇는다.
 */
public final class CoreSyntax {

    private CoreSyntax() {}

    private static HarborApi api() {
        return Harbor.api();
    }

    private static Optional<HarborPlayer> hp(Player p) {
        return api().players().get(p.getUniqueId());
    }

    public static void register(Registrar r) {
        // ---------------------------------------------------------- 플레이어 값
        r.number(
                Spec.of("harbor level"),
                (e, x) ->
                        Args.perPlayer(e, x, p -> hp(p).map(h -> (Number) h.level()).orElse(null)),
                "[the] harbor level of %players%",
                "%players%'[s] harbor level");
        r.number(
                Spec.of("harbor job tier"),
                (e, x) ->
                        Args.perPlayer(
                                e,
                                x,
                                p -> hp(p).map(h -> (Number) h.jobTier().level()).orElse(null)),
                "[the] harbor job tier of %players%",
                "%players%'[s] harbor job tier");
        r.text(
                Spec.of("harbor job line"),
                (e, x) ->
                        Args.perPlayer(
                                e,
                                x,
                                p ->
                                        hp(p).flatMap(HarborPlayer::jobLine)
                                                .map(JobLine::key)
                                                .orElse(null)),
                "[the] harbor job line of %players%",
                "%players%'[s] harbor job line");
        r.number(
                Spec.of("harbor reputation"),
                (e, x) ->
                        Args.perPlayer(
                                e, x, p -> hp(p).map(h -> (Number) h.reputation()).orElse(null)),
                "[the] harbor reputation [points] of %players%",
                "%players%'[s] harbor reputation [points]");
        r.number(
                Spec.single("harbor season"),
                (e, x) -> List.of(api().infra().season().value()),
                "[the] harbor season [number]");

        // ---------------------------------------------------------- 플래그
        r.text(
                Spec.of("harbor flag value"),
                (e, x) -> {
                    String flag = Args.string(e, x, 1).orElse("");
                    if (!Ids.flag(flag)) {
                        return List.of();
                    }
                    return Args.perPlayer(
                            e,
                            x,
                            p -> api().flags().playerValue(p.getUniqueId(), flag).orElse(null));
                },
                "[the] harbor flag value of %players% for %string%");
        r.text(
                Spec.single("harbor server flag value"),
                (e, x) -> {
                    String flag = Args.string(e, x, 0).orElse("");
                    return Ids.flag(flag)
                            ? Args.one(api().flags().serverValue(flag).orElse(null))
                            : List.of();
                },
                "[the] harbor server flag value [of] %string%");
        r.condition(
                Spec.of("player has harbor flag"),
                (e, x) -> {
                    String flag = Args.string(e, x, 1).orElse("");
                    return Ids.flag(flag)
                            && Args.all(e, x, p -> api().flags().hasPlayer(p.getUniqueId(), flag));
                },
                "%players% (has|have) harbor flag %string%",
                "%players% (doesn't|does not|don't|do not) have harbor flag %string%");
        r.condition(
                Spec.single("harbor server flag is set"),
                (e, x) -> {
                    String flag = Args.string(e, x, 0).orElse("");
                    return Ids.flag(flag) && api().flags().hasServer(flag);
                },
                "harbor server flag %string% is set",
                "harbor server flag %string% (isn't|is not) set");
        r.effect(
                Spec.of("set harbor flag"),
                (e, x) -> setFlag(e, x, FlagService.Scope.PERM),
                "harbor set flag %string% for %players% [to %-string%]");
        r.effect(
                Spec.of("set seasonal harbor flag"),
                (e, x) -> setFlag(e, x, FlagService.Scope.SEASON),
                "harbor set seasonal flag %string% for %players% [to %-string%]");
        r.effect(
                Spec.of("unset harbor flag"),
                (e, x) -> {
                    String flag = Args.string(e, x, 0).orElse("");
                    if (Ids.flag(flag)) {
                        for (Player p : Args.players(e, x, 1)) {
                            var _ = api().flags().unsetPlayer(p.getUniqueId(), flag);
                        }
                    }
                },
                "harbor (unset|clear) flag %string% (for|of|from) %players%");
        r.effect(
                Spec.single("set harbor server flag"),
                (e, x) -> {
                    String flag = Args.string(e, x, 0).orElse("");
                    if (Ids.flag(flag)) {
                        var _ =
                                api().flags()
                                        .setServer(flag, Args.string(e, x, 1).orElse("1"), false);
                    }
                },
                "harbor set server flag %string% [to %-string%]");
        r.effect(
                Spec.single("unset harbor server flag"),
                (e, x) -> {
                    String flag = Args.string(e, x, 0).orElse("");
                    if (Ids.flag(flag)) {
                        var _ = api().flags().unsetServer(flag);
                    }
                },
                "harbor (unset|clear) server flag %string%");

        // ---------------------------------------------------------- 화면
        r.effect(
                Spec.of("show harbor title"),
                (e, x) -> {
                    Component title = mini(Args.string(e, x, 0).orElse(""));
                    Component sub = mini(Args.string(e, x, 1).orElse(""));
                    Duration stay =
                            Duration.ofMillis(
                                    Math.round(
                                            balance().number("skript.title_seconds", 2.5) * 1000));
                    for (Player p : Args.players(e, x, 2)) {
                        api().ui()
                                .title(
                                        p,
                                        title,
                                        sub,
                                        Duration.ofMillis(250),
                                        stay,
                                        Duration.ofMillis(500));
                    }
                },
                "show harbor title %string% [with subtitle %-string%] to %players%");
        r.effect(
                Spec.of("show harbor actionbar"),
                (e, x) -> {
                    Component text = mini(Args.string(e, x, 0).orElse(""));
                    for (Player p : Args.players(e, x, 1)) {
                        api().ui().actionBar(p, text);
                    }
                },
                "show harbor action[ ]bar %string% to %players%");

        // ---------------------------------------------------------- 우편
        r.effect(
                Spec.of("send harbor mail"),
                (e, x) -> {
                    Optional<MailService> mail = api().mail();
                    if (mail.isEmpty()) {
                        return;
                    }
                    List<ItemStack> items = new ArrayList<>();
                    if (x.length > 0 && x[0] != null) {
                        for (Object o : x[0].getArray(e)) {
                            if (o instanceof ItemStack s && !s.isEmpty()) {
                                items.add(s.clone());
                            }
                        }
                    }
                    if (x.length < 2 || x[1] == null) {
                        return;
                    }
                    for (Object o : x[1].getArray(e)) {
                        if (o instanceof OfflinePlayer target) {
                            UUID uuid = target.getUniqueId();
                            for (ItemStack item : items) {
                                var _ =
                                        mail.get()
                                                .sendItem(
                                                        uuid,
                                                        item.clone(),
                                                        "skript",
                                                        "skript:" + UUID.randomUUID());
                            }
                        }
                    }
                },
                "send harbor mail %itemstacks% to %offlineplayers%");
    }

    private static void setFlag(
            Event e, ch.njol.skript.lang.Expression<?>[] x, FlagService.Scope scope) {
        String flag = Args.string(e, x, 0).orElse("");
        if (!Ids.flag(flag)) {
            return;
        }
        String value = Args.string(e, x, 2).orElse("1");
        for (Player p : Args.players(e, x, 1)) {
            var _ = api().flags().setPlayer(p.getUniqueId(), scope, flag, value);
        }
    }

    private static Component mini(String text) {
        return text.isEmpty() ? Component.empty() : MiniMessage.miniMessage().deserialize(text);
    }

    private static Balance balance() {
        return api().content().snapshot().single(Balance.TYPE);
    }
}
