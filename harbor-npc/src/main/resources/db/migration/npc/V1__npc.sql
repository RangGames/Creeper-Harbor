-- Harbor-NPC 소유 테이블 (docs 20 5.3, '11. 데이터 설계' 3·3.1·3.2장).
-- 운영 중에는 열을 지우거나 이름을 바꾸지 않고 추가만 한다.

-- NPC별 호감도. 시즌 초기화 대상 (Season reset_plan.yml에 등록). gift_day·gifts_today·greet_day는 3.2 추가 열.
CREATE TABLE npc_affinity (
    uuid        UUID        NOT NULL,
    season      INT         NOT NULL,
    npc_id      VARCHAR(96) NOT NULL,
    points      INT         NOT NULL DEFAULT 0,
    gift_day    DATE        NULL,
    gifts_today INT         NOT NULL DEFAULT 0,
    greet_day   DATE        NULL,
    PRIMARY KEY (uuid, season, npc_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 컷씬 중단 복구 (실시간). 컷씬 시작 때 즉시 쓰고 끝나면 지운다. flying·allow_flight는 (추가 제안)
CREATE TABLE cutscene_restore (
    uuid         UUID         NOT NULL PRIMARY KEY,
    server       VARCHAR(32)  NOT NULL,
    cutscene     VARCHAR(96)  NOT NULL COMMENT '(추가 제안) 재생하던 컷씬',
    world        VARCHAR(64)  NOT NULL,
    x            DOUBLE       NOT NULL,
    y            DOUBLE       NOT NULL,
    z            DOUBLE       NOT NULL,
    yaw          FLOAT        NOT NULL,
    pitch        FLOAT        NOT NULL,
    gamemode     VARCHAR(16)  NOT NULL,
    allow_flight BOOLEAN      NOT NULL DEFAULT FALSE,
    flying       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 평판 획득 기록. 같은 시즌 같은 ref_id는 한 번만 (중복 지급 차단), 출처별 하루 상한 계산, /평판 최근 내역.
-- 값 자체는 Core player_season.reputation. 시즌 초기화 대상 (Season reset_plan.yml에 등록).
CREATE TABLE npc_reputation_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid       UUID         NOT NULL,
    season     INT          NOT NULL,
    source     VARCHAR(24)  NOT NULL,
    delta      INT          NOT NULL,
    ref_id     VARCHAR(128) NULL,
    day        DATE         NOT NULL COMMENT '일일 초기화 기준 게임 날짜',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_npc_rep_ref (uuid, season, ref_id),
    INDEX idx_npc_rep_day (uuid, season, day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 시즌을 넘어 유지되는 최고 도달 평판 등급. 설계상 위치는 Common players.best_rep_tier ('11. 데이터 설계' 3.2)이고,
-- Common에 열이 생기기 전까지 NPC가 이 표에 둔다 (ReputationService.bestTier 뒤에 숨김). 이월 대상.
CREATE TABLE npc_rep_best (
    uuid       UUID         NOT NULL PRIMARY KEY,
    best_tier  INT          NOT NULL,
    reached_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
