package wiki.creeper.harbor.npc.api;

import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/** 대화 액션의 결과. */
public sealed interface ActionResult {

    /** 다음 액션으로. 모두 Continue면 선택지의 goto로 가거나, goto가 없으면 닫는다. */
    record Continue() implements ActionResult {}

    /** 남은 액션을 건너뛰고 이 노드로 간다. */
    record GoTo(String node) implements ActionResult {}

    /** 현재 노드의 page번째(0부터) 대사로 간다. */
    record Page(int page) implements ActionResult {}

    /** 대화를 닫는다. */
    record Close() implements ActionResult {}

    /** 액션이 다른 화면(GUI·확인 Dialog·컷씬)을 열었다. 대화 세션만 끝내고 화면은 건드리지 않는다. */
    record Handoff() implements ActionResult {}

    /**
     * 실패 (예: quest:accept 조건 미충족). messageKey가 있으면 채팅으로 보내고, node가 있으면 그 안내 노드로, 없으면 현재 노드를 다시
     * 보여준다.
     */
    record Fail(@Nullable String node, @Nullable String messageKey) implements ActionResult {}

    /** 비동기 결과. 끝날 때까지 이 세션의 클릭은 무시한다. 결과는 메인 스레드에서 처리한다. */
    record Async(CompletableFuture<ActionResult> result) implements ActionResult {}

    ActionResult CONTINUE = new Continue();
    ActionResult CLOSE = new Close();
    ActionResult HANDOFF = new Handoff();

    static ActionResult goTo(String node) {
        return new GoTo(node);
    }

    static ActionResult fail(@Nullable String node) {
        return new Fail(node, null);
    }

    static ActionResult failMessage(String messageKey) {
        return new Fail(null, messageKey);
    }

    static ActionResult async(CompletableFuture<ActionResult> result) {
        return new Async(result);
    }
}
