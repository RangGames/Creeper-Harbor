package wiki.creeper.harbor.npc.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.DialogRef;

/** 대화 트리 평가 (서버 API 없음). 조건 하나의 참·거짓은 호출하는 쪽이 정한다. */
public final class DialogEval {

    private DialogEval() {}

    /** 조건 목록이 모두 참인지 (비어 있으면 참). */
    public static boolean all(List<DialogRef> conditions, Predicate<DialogRef> test) {
        for (DialogRef c : conditions) {
            if (!test.test(c)) {
                return false;
            }
        }
        return true;
    }

    /** entries를 위에서부터 평가해 첫 일치 노드. */
    public static Optional<String> entry(DialogDef dialog, Predicate<DialogRef> test) {
        for (DialogDef.Entry entry : dialog.entries()) {
            if (all(entry.conditions(), test)) {
                return Optional.of(entry.node());
            }
        }
        return Optional.empty();
    }

    /** 조건이 맞는 선택지와 원래 순서 번호. */
    public record Visible(int index, DialogDef.Option option) {}

    public static List<Visible> visibleOptions(DialogDef.Node node, Predicate<DialogRef> test) {
        List<Visible> visible = new ArrayList<>();
        List<DialogDef.Option> options = node.options();
        for (int i = 0; i < options.size(); i++) {
            DialogDef.Option o = options.get(i);
            if (all(o.conditions(), test)) {
                visible.add(new Visible(i, o));
            }
        }
        return visible;
    }

    /** 이 페이지에서 선택지를 보여줄지 (마지막 페이지만, 페이지가 없으면 바로). */
    public static boolean optionsOnPage(DialogDef.Node node, int page) {
        return optionsOnPage(node.pages().size(), page);
    }

    /** 페이지 번호를 범위 안으로. */
    public static int clampPage(DialogDef.Node node, int page) {
        return clampPage(node.pages().size(), page);
    }

    /** 쪽 수만으로 (다른 모듈이 연 대사 Speech도 같은 규칙). */
    public static boolean optionsOnPage(int pageCount, int page) {
        return page >= pageCount - 1;
    }

    public static int clampPage(int pageCount, int page) {
        return Math.clamp(page, 0, Math.max(0, pageCount - 1));
    }
}
