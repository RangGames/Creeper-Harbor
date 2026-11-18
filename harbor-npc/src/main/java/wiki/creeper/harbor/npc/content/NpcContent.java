package wiki.creeper.harbor.npc.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.type.Messages;

/** NPC 콘텐츠 형식. 서버 API를 쓰지 않아 harbor-tools(CI)도 같은 코드로 검사한다. */
public final class NpcContent {

    public static final List<ContentType<?>> TYPES =
            List.of(NpcDef.TYPE, DialogDef.TYPE, CutsceneDef.TYPE);

    /** 대사·버튼 문구 키가 messages_ko.yml에 있는지. */
    public static final ContentCheck<DialogDef> DIALOG_MESSAGES =
            (dialog, check) -> {
                Messages messages = check.snapshot().single(Messages.TYPE);
                for (DialogDef.Node node : dialog.nodes().values()) {
                    for (String page : node.pages()) {
                        requireKey(messages, page, "노드 " + node.id() + " 대사", check);
                    }
                    for (DialogDef.Option option : node.options()) {
                        requireKey(messages, option.label(), "노드 " + node.id() + " 선택지", check);
                        if (option.tooltip() != null) {
                            requireKey(
                                    messages, option.tooltip(), "노드 " + node.id() + " 설명", check);
                        }
                    }
                }
            };

    /** 컷씬 대사·타이틀 키가 messages_ko.yml에 있는지. */
    public static final ContentCheck<CutsceneDef> CUTSCENE_MESSAGES =
            (cutscene, check) -> {
                Messages messages = check.snapshot().single(Messages.TYPE);
                cutscene.lines().forEach(l -> requireKey(messages, l.text(), "대사", check));
                for (CutsceneDef.TitleCue t : cutscene.titles()) {
                    requireKey(messages, t.title(), "타이틀", check);
                    if (t.subtitle() != null) {
                        requireKey(messages, t.subtitle(), "부제", check);
                    }
                }
            };

    /** 선물 반응 대사 키, 떠돌이 후보 월드 등. */
    public static final ContentCheck<NpcDef> NPC_MESSAGES =
            (npc, check) -> {
                Messages messages = check.snapshot().single(Messages.TYPE);
                npc.giftLines().values().forEach(k -> requireKey(messages, k, "선물 반응", check));
            };

    private NpcContent() {}

    private static void requireKey(
            Messages messages, String key, String what, ContentCheck.Context check) {
        if (messages.get(key).isEmpty()) {
            check.error(what + " 문구 키가 messages_ko.yml에 없습니다: " + key);
        }
    }
}
