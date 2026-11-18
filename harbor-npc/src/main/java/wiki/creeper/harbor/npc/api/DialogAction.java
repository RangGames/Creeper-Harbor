package wiki.creeper.harbor.npc.api;

/**
 * 대화 선택지 액션 (docs 20 5.2). 선택지의 actions를 위에서부터 실행하고, 결과가 {@link ActionResult.Continue}가 아니면 남은 액션을
 * 건너뛴다. 메인 스레드에서 실행한다. DB가 필요하면 {@link ActionResult#async}를 돌려준다.
 *
 * <p>아이템을 직접 주는 액션은 두지 않는다: 지급은 퀘스트 보상으로만 한다 (대화 반복 복사 경로 차단).
 */
@FunctionalInterface
public interface DialogAction {

    ActionResult run(DialogContext ctx, DialogArgs args);

    /** 콘텐츠 검사 단계에서 인자를 확인한다. 틀리면 check.error로 알린다. */
    default void validate(DialogArgs args, ArgCheck check) {}
}
