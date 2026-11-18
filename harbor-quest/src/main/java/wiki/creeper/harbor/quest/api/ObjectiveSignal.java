package wiki.creeper.harbor.quest.api;

import org.bukkit.entity.Player;

/**
 * 이벤트를 만들기 어려운 모듈이 목표를 진행시키는 창구 (docs 20 6.2). 메인 스레드에서 부른다.
 *
 * <pre>{@code
 * signals.signal(player, "chart_percent", "region:harbor:region/coral_isles", 1);  // 센다
 * signals.set(player, "chart_percent", "region/coral_isles", 42);                // 도달 값
 * }</pre>
 *
 * target은 목표 대상 표기(ID, region:…, any)와 비교한다. 목표가 any면 모든 신호를 받는다.
 */
public interface ObjectiveSignal {

    /** 수를 더한다 (catch·use_item 같은 세는 목표). */
    void signal(Player player, String type, String target, int amount);

    /** 도달 값으로 둔다 (codex·chart_percent 같은 게이지 목표). 내려가지는 않는다. */
    void set(Player player, String type, String target, int value);
}
