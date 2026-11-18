-- Harbor-Proxy 소유 테이블 (docs 20 1.3, 모두 추가 제안): 제재, 연결 계정, 공유 IP.

-- 접속·채팅 제재. expires_at이 NULL이면 영구. 해제는 행을 지우지 않고 revoked = 1 (기록 보존).
-- issued_at·revoked_by·revoked_at은 감사용 (추가 제안).
CREATE TABLE sanctions (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid       UUID         NOT NULL,
    type       VARCHAR(8)   NOT NULL COMMENT 'BAN · MUTE',
    reason     VARCHAR(255) NOT NULL,
    issued_by  VARCHAR(36)  NOT NULL COMMENT 'UUID 또는 CONSOLE',
    issued_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at TIMESTAMP(3) NULL,
    revoked    TINYINT(1)   NOT NULL DEFAULT 0,
    revoked_by VARCHAR(36)  NULL,
    revoked_at TIMESTAMP(3) NULL,
    INDEX idx_sanctions_active (uuid, type, revoked)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 같은 ip_hash로 들어온 계정 쌍. uuid_a < uuid_b (UUID 문자열 순)로 정렬해 중복을 막는다.
-- 읽기는 Common AccountLinkReader (Economy·Fishing 거래 감시).
CREATE TABLE alt_link (
    uuid_a     UUID         NOT NULL,
    uuid_b     UUID         NOT NULL,
    ip_hash    CHAR(64)     NOT NULL,
    first_seen TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    hits       INT          NOT NULL DEFAULT 1,
    PRIMARY KEY (uuid_a, uuid_b, ip_hash),
    INDEX idx_alt_link_b (uuid_b),
    INDEX idx_alt_link_ip (ip_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- PC방·학교처럼 공유 IP로 확인된 곳. 다계정 경보에서 뺀다.
CREATE TABLE ip_shared (
    ip_hash  CHAR(64)     NOT NULL PRIMARY KEY,
    label    VARCHAR(64)  NOT NULL,
    added_by VARCHAR(36)  NOT NULL,
    added_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- /hp lookup·ban 등에서 접속 중이 아닌 이름을 UUID로 찾는다 (로비에만 온 계정은 players에 없음).
CREATE INDEX idx_join_log_name ON join_log (name, joined_at);
