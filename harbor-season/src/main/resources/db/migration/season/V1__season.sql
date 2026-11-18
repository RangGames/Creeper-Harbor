-- Harbor-Season 소유 테이블 (docs 20 11.3, '11. 데이터 설계' 3·3.1).
-- weekly_counter는 시즌 초기화, 나머지는 이월. 운영 중에는 열 추가만 한다.

-- 시즌 상태 (추가 제안). 상태 전이는 기대 상태 조건부 UPDATE로만 한다.
CREATE TABLE season_meta (
    season           INT          NOT NULL PRIMARY KEY,
    starts_at        TIMESTAMP(3) NOT NULL,
    ends_at          TIMESTAMP(3) NULL,
    state            VARCHAR(16)  NOT NULL DEFAULT 'PLANNED' COMMENT 'PLANNED · ACTIVE · TRANSITIONING · ARCHIVED',
    week_shift_hours INT          NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 주간 횟수 (추가 제안). 키 boss:<boss_id> (Boss 주간 보상), craft:<recipe_id> (Economy 주간 제작)
CREATE TABLE weekly_counter (
    uuid       UUID         NOT NULL,
    season     INT          NOT NULL,
    week       INT          NOT NULL,
    `key`      VARCHAR(96)  NOT NULL,
    count      INT          NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, season, week, `key`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 칭호 ('11. 데이터 설계' 3장). season = 얻은 시즌 (표시용, 초기화 대상 아님). ref_id는 (추가 제안) 출처 참조 ID
CREATE TABLE titles (
    uuid        UUID         NOT NULL,
    title_id    VARCHAR(96)  NOT NULL,
    season      INT          NOT NULL,
    obtained_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ref_id      VARCHAR(64)  NULL,
    PRIMARY KEY (uuid, title_id),
    INDEX idx_titles_title (title_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 플레이어별 Season 상태: 마지막으로 정리(SeasonGate)한 시즌, 장착 칭호
CREATE TABLE season_player (
    uuid           UUID         NOT NULL PRIMARY KEY,
    last_season    INT          NOT NULL DEFAULT 0,
    equipped_title VARCHAR(96)  NULL,
    updated_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 랭킹 마감본 (추가 제안). week = 0이면 시즌 최종본. name은 (추가 제안) 당시 표시 이름
CREATE TABLE ranking_snapshot (
    season   INT          NOT NULL,
    week     INT          NOT NULL,
    board    VARCHAR(48)  NOT NULL,
    league   VARCHAR(8)   NOT NULL,
    `rank`   INT          NOT NULL,
    uuid     UUID         NULL,
    name     VARCHAR(64)  NOT NULL,
    value    DOUBLE       NOT NULL,
    taken_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (season, week, board, league, `rank`),
    INDEX idx_ranking_snapshot_player (uuid, season)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 명예의 전당 (추가 제안). subject = 어종·보스 ID ('-' = 대상 없음), final = 0이면 진행 중 잠정 기록
CREATE TABLE hall_of_fame (
    season       INT          NOT NULL,
    category     VARCHAR(48)  NOT NULL,
    subject      VARCHAR(96)  NOT NULL,
    `rank`       INT          NOT NULL,
    uuid         UUID         NOT NULL,
    name_at_time VARCHAR(64)  NOT NULL,
    value        DOUBLE       NOT NULL,
    detail       TEXT         NULL COMMENT 'JSON',
    recorded_at  TIMESTAMP(3) NOT NULL,
    `final`      TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (season, category, subject, `rank`),
    INDEX idx_hall_of_fame_player (season, category, uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 시즌 전환 체크포인트 일지 (추가 제안). step = 큰 단계(PRECHECK …) 또는 세부 단계(ARCHIVE:<테이블>, RESET:currency, HOOK:<id>:<단계>)
CREATE TABLE season_transition_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    transition_id VARCHAR(36)  NOT NULL,
    from_season   INT          NOT NULL,
    to_season     INT          NOT NULL,
    step          VARCHAR(96)  NOT NULL,
    state         VARCHAR(16)  NOT NULL COMMENT 'STARTED · DONE · FAILED · SKIPPED',
    `rows`        BIGINT       NOT NULL DEFAULT 0,
    checksum      BIGINT       NOT NULL DEFAULT 0,
    detail        TEXT         NULL,
    started_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at   TIMESTAMP(3) NULL,
    operator      VARCHAR(36)  NOT NULL,
    UNIQUE KEY uq_transition_step (transition_id, step),
    INDEX idx_transition_from (from_season, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
