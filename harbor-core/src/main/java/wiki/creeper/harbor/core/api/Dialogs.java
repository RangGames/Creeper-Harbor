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
}
