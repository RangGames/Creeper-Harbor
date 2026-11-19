package wiki.creeper.harbor.core.message;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.core.api.Ui;

/** 액션바·보스바·타이틀 도우미, 커스텀 폰트 글리프 등록표 ({@code <glyph:이름>}). */
public final class UiManager implements Ui {

    private final Map<String, String> glyphs = new ConcurrentHashMap<>();

    /** 마지막 일반 액션바 시각 (나간 Player 객체는 GC로 빠진다). */
    private final Map<Player, Long> lastActionBar =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final TagResolver glyphResolver =
            TagResolver.resolver(
                    "glyph",
                    (args, ctx) -> {
                        String name = args.popOr("글리프 이름이 필요합니다").value();
                        String glyph = glyphs.get(name);
                        if (glyph == null) {
                            throw ctx.newException("등록되지 않은 글리프: " + name, args);
                        }
                        return Tag.selfClosingInserting(Component.text(glyph));
                    });

    @Override
    public void actionBar(Player player, Component message) {
        lastActionBar.put(player, System.nanoTime());
        player.sendActionBar(message);
    }

    @Override
    public boolean ambientActionBar(Player player, Component message, Duration quiet) {
        Long last = lastActionBar.get(player);
        if (last != null && System.nanoTime() - last < quiet.toNanos()) {
            return false;
        }
        player.sendActionBar(message);
        return true;
    }

    @Override
    public void title(
            Player player,
            Component title,
            Component subtitle,
            Duration fadeIn,
            Duration stay,
            Duration fadeOut) {
        player.showTitle(Title.title(title, subtitle, Title.Times.times(fadeIn, stay, fadeOut)));
    }

    @Override
    public BossBar showBossBar(Player player, Component name, float progress, BossBar.Color color) {
        BossBar bar =
                BossBar.bossBar(
                        name, Math.clamp(progress, 0f, 1f), color, BossBar.Overlay.PROGRESS);
        player.showBossBar(bar);
        return bar;
    }

    @Override
    public void hideBossBar(Player player, BossBar bar) {
        player.hideBossBar(bar);
    }

    @Override
    public void registerGlyph(String name, String glyph) {
        glyphs.put(name, glyph);
    }

    @Override
    public TagResolver glyphs() {
        return glyphResolver;
    }
}
