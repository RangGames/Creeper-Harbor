package wiki.creeper.harbor.core.api;

import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Dialog 화면 래퍼. Paper Dialog API는 실험 단계라 이 인터페이스 뒤에 숨긴다. API가 바뀌어도 Core만 고친다. */
public interface Dialogs {

    /** 안내 화면 (확인 버튼 하나). */
    void notice(Player player, Component title, List<Component> body);

    /** 예·아니오 화면. 누른 버튼의 결과를 메인 스레드에서 받는다 (ESC로 닫으면 결과가 오지 않는다). */
    void confirm(
            Player player,
            Component title,
            List<Component> body,
            Component yes,
            Component no,
            Consumer<Boolean> result);

    /** 선택지 하나. */
    record Choice(Component label, Component tooltip) {}

    /**
     * 여러 선택지 중 하나를 고르는 화면 (2차 전직 계열 선택 등). 고른 순서 번호(0부터)를 메인 스레드에서 받는다.
     *
     * @param columns 한 줄에 놓을 버튼 수
     */
    void choose(
            Player player,
            Component title,
            List<Component> body,
            List<Choice> choices,
            int columns,
            Consumer<Integer> result);

    /**
     * 글 입력 화면 (추가 제안, 퀘스트 편집기 등). 확인을 누르면 입력값(앞뒤 공백 제거)을 메인 스레드에서 받는다. 취소·ESC면 결과가 오지 않는다.
     *
     * @param initial 처음 채워 둘 값
     * @param maxLength 최대 글자 수
     * @param multiline 여러 줄 입력 (줄바꿈은 그대로 받는다)
     */
    void input(
            Player player,
            Component title,
            List<Component> body,
            Component label,
            String initial,
            int maxLength,
            boolean multiline,
            Component confirm,
            Component cancel,
            Consumer<String> result);
}
