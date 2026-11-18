package wiki.creeper.harbor.npc.api;

import wiki.creeper.harbor.common.content.ContentSnapshot;

/** 조건·액션 인자 검사 도구 (콘텐츠 검사 단계). 오류가 하나라도 있으면 리로드를 거부한다. */
public interface ArgCheck {

    /** 새로 만들어질 스냅샷 (퀘스트 ID 존재 확인 등). */
    ContentSnapshot snapshot();

    /** 오류를 기록한다. 대화 파일·노드·선택지 위치가 붙는다. */
    void error(String message);
}
