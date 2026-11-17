-- Harbor-Common 소유 테이블 ('16. 플러그인 설계' 3장). Proxy와 Purpur가 같이 쓴다.
-- 운영 중에는 열을 지우거나 이름을 바꾸지 않고 추가만 한다.

CREATE TABLE players (
    uuid       UUID         NOT NULL PRIMARY KEY,
    name       VARCHAR(16)  NOT NULL,
    first_join TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_join  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    playtime   BIGINT       NOT NULL DEFAULT 0 COMMENT '초',
    INDEX idx_players_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 서버 이동 잠금의 감사·복구용 기록. 실제 잠금은 Redis harbor:lock:<uuid>
CREATE TABLE sessions (
    uuid         UUID         NOT NULL PRIMARY KEY,
    server       VARCHAR(32)  NOT NULL,
    locked_until TIMESTAMP(3) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE admin_log (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    admin      VARCHAR(36)  NOT NULL COMMENT 'UUID 또는 CONSOLE',
    action     VARCHAR(64)  NOT NULL,
    target     VARCHAR(64)  NULL,
    detail     TEXT         NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_admin_log_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 골드·물비늘 원장. 재화 변경은 여기로만 한다.
CREATE TABLE ledger (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid          UUID         NOT NULL,
    currency      VARCHAR(16)  NOT NULL,
    delta         BIGINT       NOT NULL,
    balance_after BIGINT       NOT NULL,
    reason        VARCHAR(32)  NOT NULL,
    ref_id        VARCHAR(64)  NULL,
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_ledger_ref (uuid, currency, reason, ref_id),
    INDEX idx_ledger_player (uuid, currency, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 진주(유료 재화) 원장. 잔액은 Shop 모듈의 pearl_wallet
CREATE TABLE pearl_ledger (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid       UUID         NOT NULL,
    delta      BIGINT       NOT NULL,
    reason     VARCHAR(32)  NOT NULL,
    ref_id     VARCHAR(64)  NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_pearl_ledger_ref (uuid, reason, ref_id),
    INDEX idx_pearl_ledger_player (uuid, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
