-- Harbor-Economy 소유 테이블 (docs 20 7.3).

-- 시세 계산용 시간별 판매량 (7일 보관)
CREATE TABLE market_sales (
    fish_id VARCHAR(96) NOT NULL,
    hour    DATETIME    NOT NULL,
    count   INT         NOT NULL,
    PRIMARY KEY (fish_id, hour),
    INDEX idx_market_sales_hour (hour)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 매시간 시세 계산 결과
CREATE TABLE market_coef (
    fish_id   VARCHAR(96)  NOT NULL,
    hour      DATETIME     NOT NULL,
    s         INT          NOT NULL,
    s0        DOUBLE       NOT NULL,
    coef      DOUBLE       NOT NULL,
    PRIMARY KEY (fish_id, hour),
    INDEX idx_market_coef_hour (hour)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 우편함: 경매 반환·낙찰품, 통발 자동 회수, 보상 재발송, 상점 구매 넘친 물건
CREATE TABLE mailbox (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid       UUID         NOT NULL,
    season     INT          NOT NULL,
    kind       VARCHAR(8)   NOT NULL COMMENT 'item · gold',
    item_data  MEDIUMBLOB   NULL,
    gold       BIGINT       NOT NULL DEFAULT 0,
    source     VARCHAR(32)  NOT NULL,
    ref_id     VARCHAR(64)  NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claimed_at TIMESTAMP(3) NULL,
    state      VARCHAR(12)  NOT NULL DEFAULT 'UNCLAIMED',
    INDEX idx_mailbox_player (uuid, state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 상점 항목 일일 구매 한도
CREATE TABLE shop_limit (
    uuid       UUID        NOT NULL,
    shop_entry VARCHAR(128) NOT NULL,
    day        DATE        NOT NULL,
    count      INT         NOT NULL,
    PRIMARY KEY (uuid, shop_entry, day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
