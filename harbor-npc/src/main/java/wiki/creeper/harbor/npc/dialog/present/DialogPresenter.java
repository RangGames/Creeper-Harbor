package wiki.creeper.harbor.npc.dialog.present;

import org.bukkit.entity.Player;

/** 대화 화면을 그리는 방식 하나 ({@link wiki.creeper.harbor.npc.api.DialogStyle}). 메인 스레드 전용. */
public interface DialogPresenter {

    /** 한 쪽을 보여준다. 같은 플레이어의 이전 쪽은 덮어쓴다. */
    void show(Player player, Frame frame);

    /** 세션이 끝났을 때 남은 표시를 지운다 (말풍선·타이틀 등). */
    default void clear(Player player) {}

    /** watch 타이머마다 (사라지는 표시를 다시 보냄, 말풍선 위치 확인). */
    default void refresh(Player player) {}
}
