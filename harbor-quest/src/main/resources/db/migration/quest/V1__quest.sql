-- Harbor-Quest 소유 테이블 (docs 20 6.3, '11. 데이터 설계' 3·3.1장).
-- quest_progress: 메인(quest_id 접두사 harbor:quest/main/) 행은 시즌 전환 때 지우지 않고 원래 season 값 그대로 둔다
-- (Season reset_plan.yml: archive_reset + keep_where). 나머지는 시즌 초기화.
-- 진행(progress)은 지연 쓰기 큐, 상태 전이(수락·완료·포기·실패)는 즉시 쓴다.
CREATE TABLE quest_progress (
    uuid       UUID         NOT NULL,
    season     INT          NOT NULL,
    quest_id   VARCHAR(96)  NOT NULL,
    -- ACTIVE · READY · COMPLETED · FAILED (ABANDONED는 행 삭제)
    state      VARCHAR(16)  NOT NULL,
    -- {v, o, i, cycle, r: {snap, paid}, a}
    progress   TEXT         NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, season, quest_id),
    -- (추가 제안) 서버 진행도: 장 클리어 인원 집계
    INDEX idx_quest_progress_quest (quest_id, season, state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 어부 길드 게시판 일일·주간 의뢰, 수집 의뢰. season 열 없음 (reset_plan scope: full로 전환 때 비움).
-- kind: daily · weekly · collect, cycle: d2027-05-03 · w1-3
CREATE TABLE quest_board (
    uuid       UUID         NOT NULL,
    kind       VARCHAR(16)  NOT NULL,
    cycle      VARCHAR(32)  NOT NULL,
    offers     TEXT         NOT NULL,
    chosen     TEXT         NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, kind, cycle)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
