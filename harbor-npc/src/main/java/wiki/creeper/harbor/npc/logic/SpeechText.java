package wiki.creeper.harbor.npc.logic;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * 대사 문구 도우미 (서버 API 없음): 타자 효과용 글자 수 자르기(꾸밈 유지), 글자 수 세기, 자동 넘김 계산. 글자는 코드 포인트로 센다 (한글 한 글자 = 1).
 */
public final class SpeechText {

    private SpeechText() {}

    /** 보이는 글자 수 (꾸밈 태그 제외). */
    public static int length(Component text) {
        String plain = PlainTextComponentSerializer.plainText().serialize(text);
        return plain.codePointCount(0, plain.length());
    }

    /** 앞에서부터 {@code chars}글자만 남긴다. 색·굵기 같은 꾸밈은 그대로 두고, 글자가 아닌 조각(번역·키 바인드)은 통째로 한 글자로 친다. */
    public static Component slice(Component text, int chars) {
        if (chars <= 0) {
            return Component.empty();
        }
        int[] budget = {chars};
        return sliceInto(text, budget);
    }

    private static Component sliceInto(Component c, int[] budget) {
        Component head;
        if (c instanceof TextComponent t) {
            String content = t.content();
            int cps = content.codePointCount(0, content.length());
            if (cps <= budget[0]) {
                budget[0] -= cps;
                head = t.children(List.of());
            } else {
                int end = content.offsetByCodePoints(0, budget[0]);
                budget[0] = 0;
                return t.content(content.substring(0, end)).children(List.of());
            }
        } else {
            budget[0] -= 1;
            head = c.children(List.of());
        }
        List<Component> kids = new ArrayList<>();
        for (Component child : c.children()) {
            if (budget[0] <= 0) {
                break;
            }
            kids.add(sliceInto(child, budget));
        }
        return head.children(kids);
    }

    /** 타자 효과 단계 수: 전체 글자를 틱당 {@code perTick}글자씩 보이면 몇 틱이 걸리는지. perTick이 0이면 0 (한 번에). */
    public static int typewriterTicks(int length, int perTick) {
        if (perTick <= 0 || length <= 0) {
            return 0;
        }
        return (length + perTick - 1) / perTick;
    }

    /** 자동 넘김 틱 (초 → 틱, 0 이하면 0). */
    public static long autoTicks(double seconds) {
        return seconds <= 0 ? 0 : Math.max(1, Math.round(seconds * 20));
    }
}
