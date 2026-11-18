package wiki.creeper.harbor.quest.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.id.QuestId;

/**
 * Harbor-Quest 공개 API (docs 20 6.2). ServicesManager에 등록된다. 메인 스레드에서 쓰고, future는 메인 스레드에서 완료된다.
 *
 * <pre>{@code
 * QuestService quests = Harbor.api().service(QuestService.class).orElseThrow();
 * if (quests.isCompleted(uuid, QuestId.parse("quest/main/ch3_kept_promise"))) { ... }
 * }</pre>
 */
public interface QuestService {

    /** 계산 상태 포함 (LOCKED·AVAILABLE). 접속 중이 아니면 LOCKED. */
    QuestState state(UUID player, QuestId quest);

    /** 진행 중(ACTIVE·READY·COMPLETING) 퀘스트. */
    List<QuestView> active(UUID player);

    /** 이번 cycle에 완료했는지 (메인은 시즌과 관계없이). */
    boolean isCompleted(UUID player, QuestId quest);

    /** 조건을 다시 확인하고 수락한다. 실패 이유는 채팅으로 알린다. */
    CompletableFuture<Boolean> accept(Player player, QuestId quest);

    /** 포기 (메인 불가). */
    CompletableFuture<Boolean> abandon(Player player, QuestId quest);

    /** 보고 (READY만). deliver 아이템 확인·제거 → 완료 → 보상. 전직 퀘스트는 Jobs 전직 화면을 연다. */
    CompletableFuture<Boolean> turnIn(Player player, QuestId quest);

    /** 추적할 퀘스트를 바꾼다 (액션바·위치 표시). */
    boolean track(Player player, QuestId quest);
}
