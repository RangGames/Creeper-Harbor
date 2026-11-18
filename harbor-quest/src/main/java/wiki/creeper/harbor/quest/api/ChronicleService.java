package wiki.creeper.harbor.quest.api;

import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/** 「항구 연대기」 (docs 20 6.4): 진실의 조각. 조각은 영구 플레이어 플래그 chronicle/&lt;id&gt;로 남는다. 메인 스레드. */
public interface ChronicleService {

    /** 모은 조각 ID (접속 중인 플레이어). */
    Set<String> pieces(UUID player);

    /** 연대기 GUI (장 다시 보기). */
    void open(Player player);
}
