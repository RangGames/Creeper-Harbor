-- Harbor-Core 소유 테이블 ('16. 플러그인 설계' 3장).
-- player_season의 직업·레벨 열은 Jobs가 Core API로 갱신한다. gold·mulbineul 열은 원장(ledger)으로만 바꾼다.

CREATE TABLE player_season (
    uuid             UUID        NOT NULL,
    season           INT         NOT NULL,
    job_line         VARCHAR(16) NULL,
    job_tier         TINYINT     NOT NULL DEFAULT 1,
    level            INT         NOT NULL DEFAULT 1,
    exp              BIGINT      NOT NULL DEFAULT 0,
    gold             BIGINT      NOT NULL DEFAULT 0,
    mulbineul_earned BIGINT      NOT NULL DEFAULT 0,
    mulbineul_spent  BIGINT      NOT NULL DEFAULT 0,
    reputation       INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, season),
    INDEX idx_player_season_level (season, level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ★5·전설·신화 어종과 보스 장비의 고유 ID 장부. 경매 등록·거래 때 대조해서 복사된 아이템을 막는다.
CREATE TABLE fish_ledger (
    item_uuid  UUID         NOT NULL PRIMARY KEY,
    fish_id    VARCHAR(96)  NOT NULL,
    size       DECIMAL(7, 1) NOT NULL,
    quality    TINYINT      NOT NULL,
    catcher    UUID         NOT NULL,
    season     INT          NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    state      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE · LISTED · RELEASED · DESTROYED',
    INDEX idx_fish_ledger_catcher (catcher)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
