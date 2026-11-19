package wiki.creeper.harbor.core.api;

import java.time.Duration;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;

/** 액션바·보스바·타이틀 출력 도우미와 커스텀 폰트 글리프 등록표. */
public interface Ui {

    void actionBar(Player player, Component message);

    /**
     * 늘 떠 있는 안내(퀘스트 방향 등)용 액션바 (추가 제안). 다른 액션바가 {@code quiet} 안에 나갔으면 보내지 않아서 그 문구를 덮지 않는다. 이 호출은
     * 다른 액션바의 시각으로 치지 않는다.
     *
     * @return 보냈으면 true
     */
    boolean ambientActionBar(Player player, Component message, Duration quiet);

    void title(
            Player player,
            Component title,
            Component subtitle,
            Duration fadeIn,
            Duration stay,
            Duration fadeOut);

    /** 보스바를 만들어 보여준다. 숨길 때는 {@link #hideBossBar}. */
    BossBar showBossBar(Player player, Component name, float progress, BossBar.Color color);

    void hideBossBar(Player player, BossBar bar);

    /** 글리프(게이지·아이콘 문자) 등록. 같은 이름이면 덮어쓴다. */
    void registerGlyph(String name, String glyph);

    /** MiniMessage에서 {@code <glyph:이름>}으로 쓰는 태그. */
    TagResolver glyphs();
}
