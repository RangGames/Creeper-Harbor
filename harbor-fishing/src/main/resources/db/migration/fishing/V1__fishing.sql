-- Harbor-Fishing 소유 테이블 (docs 20 3.3).
CREATE TABLE pity_counter (
    uuid    UUID        NOT NULL,
    season  INT         NOT NULL,
    fish_id VARCHAR(96) NOT NULL,
    casts   INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, season, fish_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 매크로 의심 기록 (감사용, 자동 제재 없음)
CREATE TABLE fishing_flags (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid       UUID         NOT NULL,
    score      INT          NOT NULL,
    reasons    TEXT         NOT NULL,
    action     VARCHAR(32)  NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_fishing_flags_uuid (uuid, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
