-- Harbor-Jobs 소유 테이블 (docs 20 2.3). 시즌 전환 때 초기화 대상.
CREATE TABLE skill_alloc (
    uuid    UUID        NOT NULL,
    season  INT         NOT NULL,
    node_id VARCHAR(96) NOT NULL,
    `rank`  INT         NOT NULL,
    PRIMARY KEY (uuid, season, node_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) capstone_week는 주간 사용 횟수를 센 시즌 주차 (주가 바뀌면 0부터)
CREATE TABLE jobs_state (
    uuid               UUID         NOT NULL,
    season             INT          NOT NULL,
    overflow_exp       BIGINT       NOT NULL DEFAULT 0,
    free_reset_used    BOOLEAN      NOT NULL DEFAULT FALSE,
    capstone_used_at   TIMESTAMP(3) NULL,
    capstone_week      INT          NOT NULL DEFAULT 0,
    capstone_week_uses INT          NOT NULL DEFAULT 0,
    tier_first_q4      BOOLEAN      NOT NULL DEFAULT FALSE,
    tier_first_q5      BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (uuid, season)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
