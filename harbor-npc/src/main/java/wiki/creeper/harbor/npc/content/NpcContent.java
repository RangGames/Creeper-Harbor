package wiki.creeper.harbor.npc.content;

import java.util.List;
import wiki.creeper.harbor.common.content.ContentCheck;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.type.Messages;

/** NPC content types. Uses no server API, so harbor-tools (CI) validates with the same code. */
public final class NpcContent {

    public static final List<ContentType<?>> TYPES =
            List.of(NpcDef.TYPE, DialogDef.TYPE, CutsceneDef.TYPE);

    /** Whether line and button text keys exist in messages_ko.yml. */
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

    /** Whether cutscene line and title keys exist in messages_ko.yml. */
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

    /** Gift reaction line keys, wanderer candidate worlds, etc. */
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
