-- Harbor-Proxy 소유 테이블 (docs 20 1.3, 추가 제안). 원문 IP는 저장하지 않고 HMAC-SHA256만 저장한다.
CREATE TABLE join_log (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid         UUID         NOT NULL,
    name         VARCHAR(16)  NOT NULL,
    ip_hash      CHAR(64)     NOT NULL,
    protocol     INT          NOT NULL,
    client_brand VARCHAR(64)  NULL,
    first_server VARCHAR(32)  NULL,
    joined_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    left_at      TIMESTAMP(3) NULL,
    kick_reason  VARCHAR(255) NULL,
    INDEX idx_join_log_uuid (uuid, joined_at),
    INDEX idx_join_log_ip (ip_hash, joined_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
