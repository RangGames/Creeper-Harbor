-- Harbor-Economy 2단계: 경매장, 우편함 중복 방지·보류, 부정 거래 감시, 시세 예측 (docs 20 7.3, '11. 데이터 설계' 3·3.1·3.2).
-- 운영 중 스키마는 열·색인 추가만 한다. 제작 주간 제한은 Season weekly_counter를 쓰므로 craft_limit 테이블은 두지 않는다.

-- 우편함: 같은 (수신자, 출처, 참조 ID) 우편은 한 번만 (Fishing 통발 회수·경매 반환 재시도 대비).
-- ref_id가 NULL인 우편(관리자 재발송, 상점 넘침)은 유니크 검사에서 빠진다 (MariaDB는 NULL끼리 같지 않음).
ALTER TABLE mailbox
    ADD COLUMN hold_until TIMESTAMP(3) NULL COMMENT '(추가 제안) 보류 해제 시각 (state HELD일 때, NULL이면 관리자 해제 대기)',
    ADD UNIQUE KEY uq_mailbox_ref (uuid, source, ref_id);

-- (추가 제안) 시세 예측 (경제 계열 '시세 예측'이 열람)
ALTER TABLE market_coef
    ADD COLUMN next_coef DOUBLE NULL;

-- 경매 등록 ('11. 데이터 설계' 3장, 시즌 전환 때 scope: full로 비움 — season/reset_plan.yml)
-- state = ACTIVE · SOLD · EXPIRED · CANCELLED · RETURNED (RETURNED는 예약: 운영 수동 반환)
CREATE TABLE auction_listing (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    seller        UUID         NOT NULL,
    seller_name   VARCHAR(16)  NOT NULL COMMENT '(추가 제안) 목록 표시용',
    item_uuid     UUID         NULL COMMENT '고유 ID (★5·전설·신화, fish_ledger)',
    item_id       VARCHAR(96)  NOT NULL COMMENT '어종 ID 또는 아이템 ID (검색·분류)',
    item_name     VARCHAR(64)  NOT NULL COMMENT '(추가 제안) 검색용 이름',
    category      VARCHAR(16)  NOT NULL COMMENT '(추가 제안) fish · gear · bait · material · food · other',
    grade         VARCHAR(12)  NOT NULL COMMENT '(추가 제안) 등급 (시세 지수)',
    trap_src      BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '(추가 제안) 통발 수확물 (부계정 감시)',
    item_data     MEDIUMBLOB   NOT NULL,
    amount        INT          NOT NULL,
    price         BIGINT       NOT NULL,
    ref_price     BIGINT       NOT NULL COMMENT '(추가 제안) 등록 때 기준가 (이상 가격 감시)',
    fee           BIGINT       NOT NULL COMMENT '등록비 (AUCTION_LIST_FEE, 환불 없음)',
    sale_fee_rate DOUBLE       NOT NULL COMMENT '(추가 제안) 등록 때 판매자의 수수료율 (판매자가 오프라인이어도 같은 비율)',
    server        VARCHAR(32)  NOT NULL COMMENT '(추가 제안) 등록한 서버',
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at    TIMESTAMP(3) NOT NULL,
    state         VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE',
    ended_at      TIMESTAMP(3) NULL,
    -- (추가 제안) ACTIVE일 때만 item_uuid. 같은 고유 아이템의 두 번째 등록을 DB가 막는다
    active_uid    UUID AS (CASE WHEN state = 'ACTIVE' THEN item_uuid END) PERSISTENT,
    UNIQUE KEY uq_auction_active_uid (active_uid),
    INDEX idx_auction_browse (state, category, price),
    INDEX idx_auction_item (state, item_id, price),
    INDEX idx_auction_seller (seller, state),
    INDEX idx_auction_expires (state, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 경매 낙찰
CREATE TABLE auction_sale (
    listing_id BIGINT       NOT NULL PRIMARY KEY,
    buyer      UUID         NOT NULL,
    seller     UUID         NOT NULL COMMENT '(추가 제안) 같은 두 사람 사이 거래 수 집계',
    price      BIGINT       NOT NULL,
    fee        BIGINT       NOT NULL,
    held       BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '(추가 제안) 판매 대금 보류 여부',
    sold_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_auction_sale_pair (seller, buyer, sold_at),
    INDEX idx_auction_sale_time (sold_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 부정 거래 의심 기록 (이월)
CREATE TABLE trade_flag (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    kind        VARCHAR(24)  NOT NULL COMMENT 'linked · pair_repeat · price_outlier · wash · trap_alt',
    buyer       UUID         NOT NULL,
    seller      UUID         NOT NULL,
    ref_id      VARCHAR(64)  NOT NULL COMMENT 'auction:<listing id>',
    score       DOUBLE       NOT NULL,
    detail      VARCHAR(255) NOT NULL,
    held        BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_by VARCHAR(32)  NULL,
    resolved_at TIMESTAMP(3) NULL,
    UNIQUE KEY uq_trade_flag_ref (ref_id),
    INDEX idx_trade_flag_open (resolved_by, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
