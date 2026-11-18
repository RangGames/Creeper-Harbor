package wiki.creeper.harbor.season.api;

/**
 * 시즌 전환 훅 (docs 20 11.2). 코드가 필요한 정리를 모듈이 구현해 {@link SeasonHookRegistry}에 등록한다. 예: Shop이
 * onArchive에서 미수령 시즌 패스 보상을 자동 지급하고 패스를 마감한다.
 *
 * <p>전환이 중간에 끊기면 resume이 같은 단계를 다시 실행하므로 <b>여러 번 불려도 결과가 같아야 한다</b> (참조 ID 유니크, INSERT IGNORE 등).
 * 예외를 던지면 전환이 그 단계에서 멈추고, 관리자가 고친 뒤 resume한다.
 *
 * <ul>
 *   <li>onArchive: ARCHIVE 단계 끝 (archive_s<n> 복사 뒤, 원본 삭제 전)
 *   <li>onReset: RESET 단계 끝 (reset_plan.yml 삭제·원장 정리 뒤)
 *   <li>onOpen: OPEN 단계 (새 시즌 ACTIVE 직후, 서버 재시작 전)
 * </ul>
 */
public interface SeasonHook {

    /** 기록용 이름 (예: shop.pass). */
    String id();

    default void onArchive(SeasonHookContext ctx) throws Exception {}

    default void onReset(SeasonHookContext ctx) throws Exception {}

    default void onOpen(SeasonHookContext ctx) throws Exception {}
}
