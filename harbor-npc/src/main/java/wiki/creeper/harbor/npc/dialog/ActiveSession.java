package wiki.creeper.harbor.npc.dialog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.npc.api.DialogStyle;
import wiki.creeper.harbor.npc.api.Speech;
import wiki.creeper.harbor.npc.content.DialogDef;

/** 열려 있는 대화 (저장하지 않음). 대화 트리(def) 세션이거나, 다른 모듈이 연 대사(speech) 세션이다. 메인 스레드 전용. */
final class ActiveSession {

    /** speech 세션이 DialogSession·이벤트에 쓰는 대화 ID. */
    static final HarborId SPEECH_ID = HarborId.parse("dialog/speech");

    /** 버튼 하나가 하는 일. */
    sealed interface Button permits Next, Choose, CloseButton {}

    record Next() implements Button {}

    record Choose(int option) implements Button {}

    record CloseButton() implements Button {}

    final UUID player;
    final NpcId npc;

    /** 시작할 때 잡은 대화 정의 스냅샷 (리로드돼도 이 세션은 끝까지 같은 정의). speech 세션이면 null. */
    final @Nullable DialogDef def;

    /** 다른 모듈이 연 대사 (대화 트리 세션이면 null). */
    final @Nullable Speech speech;

    /** speech 결과 (한 번 부르면 null로 비운다). */
    @Nullable IntConsumer onSpeech;

    final Instant openedAt;
    final boolean preview;
    String node;
    int page;

    /** 마지막으로 그린 쪽의 방식과 버튼 (DIALOG 밖의 방식에서 NPC 재클릭 처리). */
    DialogStyle style = DialogStyle.DIALOG;

    List<Button> buttons = List.of();

    /** 마지막으로 화면을 띄운 시각. 시간 제한이 지나면 클릭을 받지 않는다. */
    Instant shownAt = Instant.now();

    long nonce;
    boolean busy;

    /** 자동 넘김 예약 (쪽이 바뀌면 취소). */
    @Nullable BukkitTask auto;

    ActiveSession(UUID player, NpcId npc, DialogDef def, String node, boolean preview) {
        this.player = player;
        this.npc = npc;
        this.def = def;
        this.speech = null;
        this.node = node;
        this.preview = preview;
        this.openedAt = Instant.now();
    }

    ActiveSession(UUID player, NpcId npc, Speech speech, IntConsumer onSpeech) {
        this.player = player;
        this.npc = npc;
        this.def = null;
        this.speech = speech;
        this.onSpeech = onSpeech;
        this.node = "";
        this.preview = false;
        this.openedAt = Instant.now();
    }

    HarborId dialogId() {
        DialogDef d = def;
        return d == null ? SPEECH_ID : d.id();
    }

    void cancelAuto() {
        BukkitTask t = auto;
        if (t != null) {
            t.cancel();
            auto = null;
        }
    }

    /** speech 결과를 한 번만 넘긴다. */
    void resolve(int choice) {
        IntConsumer callback = takeCallback();
        if (callback != null) {
            callback.accept(choice);
        }
    }

    /** speech 결과 콜백을 꺼낸다 (이후 resolve는 아무것도 하지 않음). */
    @Nullable IntConsumer takeCallback() {
        IntConsumer callback = onSpeech;
        onSpeech = null;
        return callback;
    }
}
