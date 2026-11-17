package wiki.creeper.harbor.common.content;

import wiki.creeper.harbor.common.util.SourcePos;

/**
 * 파싱과 참조 검사가 끝난 뒤 실행하는 추가 검사. 다른 항목과 비교하는 검사(도감 번호 중복 등)나 서버 API가 필요한 검사(바닐라 아이템 ID 확인 등)를 여기에 둔다.
 */
@FunctionalInterface
public interface ContentCheck<T> {

    void check(T value, Context ctx);

    /** 검사 중 쓸 수 있는 정보. */
    interface Context {

        /** 새로 만들어질 스냅샷 (아직 적용 전). */
        ContentSnapshot snapshot();

        /** 검사 대상 항목의 위치. */
        SourcePos pos();

        /** 검사 대상 항목 위치에 오류를 기록한다. */
        void error(String message);
    }
}
