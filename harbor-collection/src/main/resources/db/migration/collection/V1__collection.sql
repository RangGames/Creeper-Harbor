-- Harbor-Collection 소유 테이블 (docs 20 4.3). codex_season·aquarium_species·aquarium_state는 시즌 초기화 대상.
CREATE TABLE codex_total (
    uuid         UUID          NOT NULL,
    fish_id      VARCHAR(96)   NOT NULL,
    first_caught TIMESTAMP(3)  NOT NULL,
    max_size     DECIMAL(7, 1) NOT NULL,
    best_quality TINYINT       NOT NULL,
    catch_count  INT           NOT NULL,
    PRIMARY KEY (uuid, fish_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE codex_season (
    uuid         UUID          NOT NULL,
    season       INT           NOT NULL,
    fish_id      VARCHAR(96)   NOT NULL,
    first_caught TIMESTAMP(3)  NOT NULL,
    max_size     DECIMAL(7, 1) NOT NULL,
    best_quality TINYINT       NOT NULL,
    catch_count  INT           NOT NULL,
    PRIMARY KEY (uuid, season, fish_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 한 행 = 그 리그의 개인 최고 기록 (league: tier1 · tier2 · all)
CREATE TABLE records (
    fish_id   VARCHAR(96)   NOT NULL,
    season    INT           NOT NULL,
    league    VARCHAR(8)    NOT NULL,
    uuid      UUID          NOT NULL,
    size      DECIMAL(7, 1) NOT NULL,
    caught_at TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (fish_id, season, league, uuid),
    INDEX idx_records_rank (fish_id, season, league, size)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 힌트 단계
CREATE TABLE codex_hint (
    uuid       UUID         NOT NULL,
    fish_id    VARCHAR(96)  NOT NULL,
    level      TINYINT      NOT NULL,
    source     VARCHAR(32)  NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, fish_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 수족관
CREATE TABLE aquarium_donation (
    id         BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    season     INT           NOT NULL,
    uuid       UUID          NOT NULL,
    fish_id    VARCHAR(96)   NOT NULL,
    size       DECIMAL(7, 1) NOT NULL,
    quality    TINYINT       NOT NULL,
    item_uuid  UUID          NULL,
    points     INT           NOT NULL,
    donated_at TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_aquarium_donation_player (season, uuid, fish_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE aquarium_species (
    season      INT          NOT NULL,
    fish_id     VARCHAR(96)  NOT NULL,
    first_donor UUID         NOT NULL,
    donated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (season, fish_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE aquarium_state (
    season        INT          NOT NULL PRIMARY KEY,
    points        BIGINT       NOT NULL DEFAULT 0,
    species_count INT          NOT NULL DEFAULT 0,
    stage         INT          NOT NULL DEFAULT 0,
    updated_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
