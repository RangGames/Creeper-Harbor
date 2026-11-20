package wiki.creeper.harbor.npc.api;

import java.util.Optional;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
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

    // ------------------------------------------------------------------ 보여주는 방식 (추가 제안)

    /** NPC 기본 방식: npcs.yml dialog_style → balance.yml npc.dialog.default_style → DIALOG. */
    DialogStyle styleOf(NpcId npc);

    /**
     * 대화 트리 없이 대사·선택지를 NPC 입으로 보여준다 (퀘스트 수락 제안 등). 세션·nonce·시간 제한·거리 검사는 대화 트리와 같다. 고른 선택지 번호(0부터)를
     * 메인 스레드에서 받고, 닫기·만료·멀어짐·다른 대화로 바뀜이면 -1을 받는다. 결과는 꼭 한 번만 온다.
     */
    void present(Player player, NpcId npc, Speech speech, IntConsumer onChoice);

    /**
     * 세션 없는 한마디 (말풍선·타이틀·액션바·채팅으로 몇 초). 보고 완료 인사 같은 데 쓴다.
     *
     * @param style null이면 NPC 기본 방식. DIALOG면 안내 화면
     */
    void say(Player player, NpcId npc, Component line, @Nullable DialogStyle style);
}
