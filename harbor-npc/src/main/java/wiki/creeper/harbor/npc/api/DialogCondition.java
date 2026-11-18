package wiki.creeper.harbor.npc.api;

/**
 * 대화 조건 (docs 20 5.4). 기본 키: affinity_min, rep_tier_min, flag, not_flag, server_flag,
 * not_server_flag, job_tier, level_min, time, weather. Quest는 quest_state·quest_available을 등록한다. 메인
 * 스레드에서 메모리만 읽어야 한다 (클릭 처리 0.5ms 이하).
 */
@FunctionalInterface
public interface DialogCondition {

    boolean test(DialogContext ctx, DialogArgs args);

    /** 콘텐츠 검사 단계에서 인자를 확인한다. 틀리면 check.error로 알린다 (줄 번호와 함께 리로드 거부). */
    default void validate(DialogArgs args, ArgCheck check) {}
}
