package wiki.creeper.harbor.npc.api;

import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.id.NpcId;

/**
 * 대화 엔진 (docs 20 5.4). 대화 트리는 content/dialogs/*.yml, 화면은 Core Dialog 래퍼로 그린다.
 *
 * <p>조건·액션 키는 {@code 모듈:이름} 형식으로 등록한다 (예: quest:accept, shop:open, job:change_line). 모듈의 enable(콘텐츠
 * 로드 전)에서 등록해야 콘텐츠 검사가 인자까지 확인한다. 선택 의존 모듈이 없어 키가 등록되지 않았으면 그 선택지는 숨기고 경고만 남긴다.
 *
 * <pre>{@code
 * DialogService dialogs = Harbor.api().service(DialogService.class).orElseThrow();
 * dialogs.registerAction(this, "quest:accept", (ctx, args) -> {
 *     QuestId quest = QuestId.parse(args.value().orElseThrow());
 *     return accept(ctx.player(), quest) ? ActionResult.goTo("accepted") : ActionResult.fail("not_ready");
 * });
 * dialogs.registerCondition(this, "quest_state", (ctx, args) -> ...);
 * }</pre>
 */
public interface DialogService {

    /** NPC 대화를 연다. entries를 위에서부터 평가해 첫 일치 노드를 보여준다. 대화가 없거나 일치 노드가 없으면 false. */
    boolean open(Player player, NpcId npc);

    /** 특정 노드를 바로 연다 (다른 모듈의 후속 대화, 대사 검수). */
    boolean open(Player player, NpcId npc, String node);

    /** 세션을 끝내고 화면을 닫는다. */
    void close(Player player);

    Optional<DialogSession> session(Player player);

    /** 이 NPC와 대화 세션이 열린 플레이어가 있는지 (떠돌이 이동 지연). */
    boolean hasSessionWith(NpcId npc);

    /** 같은 키가 이미 있으면 IllegalStateException. owner가 꺼지면 자동으로 지운다. */
    void registerAction(Plugin owner, String key, DialogAction action);

    void registerCondition(Plugin owner, String key, DialogCondition condition);

    boolean hasAction(String key);

    boolean hasCondition(String key);
}
