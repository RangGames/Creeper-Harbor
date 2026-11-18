-- Harbor-Shop 소유 테이블 (docs 20 12.3, '11. 데이터 설계' 3·3.1·3.2장).
-- 결제·환불 기록은 전자상거래법 보관 기간(5년) 이상 남기고 시즌 전환에서 지우지 않는다 (reset_plan: keep).
-- season 열이 있는 테이블(season_pass, season_pass_claim, chest_pity)은 content/season/reset_plan.yml에 있다.
-- 운영 중에는 열을 지우거나 이름을 바꾸지 않고 추가만 한다.

-- 진주 지갑. 잔액은 Common LedgerWriter(원장 pearl_ledger)로만 바꾼다. paid_balance·debt는 추가 제안 ('11. 데이터 설계' 3.2)
CREATE TABLE pearl_wallet (
    uuid         UUID         NOT NULL PRIMARY KEY,
    balance      BIGINT       NOT NULL DEFAULT 0,
    paid_balance BIGINT       NOT NULL DEFAULT 0 COMMENT '유료 묶음 잔액 (청약철회 판단)',
    debt         BIGINT       NOT NULL DEFAULT 0 COMMENT '환불·지불 거절 뒤 부족분. 잔액은 음수가 되지 않는다',
    updated_at   TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- pearl_ledger(Common 소유)에 ledger와 같은 형식의 열을 더한다 ('11. 데이터 설계' 3.2: Shop 소유 열). LedgerWriter가 행을 넣은 뒤 같은
-- 트랜잭션에서 Shop이 채운다.
ALTER TABLE pearl_ledger
    ADD COLUMN IF NOT EXISTS balance_after BIGINT NULL,
    ADD COLUMN IF NOT EXISTS paid_delta    BIGINT NULL COMMENT '유료 묶음 변화량';

-- 결제 건별 남은 진주 (추가 제안, 청약철회 판단). 무료 묶음(패스·이벤트) 먼저, 그다음 오래된 유료 묶음부터 쓴다
CREATE TABLE pearl_lot (
    lot_id     BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid       UUID         NOT NULL,
    source_ref VARCHAR(64)  NOT NULL COMMENT '결제 tx_id 또는 지급 참조 ID',
    paid       TINYINT(1)   NOT NULL,
    amount     BIGINT       NOT NULL,
    remaining  BIGINT       NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_pearl_lot_source (uuid, source_ref),
    INDEX idx_pearl_lot_open (uuid, remaining)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Tebex 결제 건. 장바구니에 품목이 여럿이면 tx_id#순번으로 나눠 기록한다
CREATE TABLE purchases (
    tx_id           VARCHAR(80)  NOT NULL PRIMARY KEY,
    uuid            UUID         NULL,
    product         VARCHAR(96)  NULL,
    kind            VARCHAR(16)  NULL COMMENT '(추가 제안) pearls · pearls_custom · subscription · festival',
    amount_krw      BIGINT       NOT NULL DEFAULT 0,
    pearls          BIGINT       NOT NULL DEFAULT 0,
    state           VARCHAR(16)  NOT NULL COMMENT 'RECEIVED · GRANTED · HELD · REFUNDED · CHARGEBACK · CHARGEBACK_WON',
    reversed_pearls BIGINT       NOT NULL DEFAULT 0 COMMENT '(추가 제안) 환불·지불 거절로 회수한 진주',
    debt_added      BIGINT       NOT NULL DEFAULT 0 COMMENT '(추가 제안) 회수 부족분으로 더한 debt',
    detail          TEXT         NULL COMMENT '(추가 제안) HELD 사유 등',
    created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_purchases_player (uuid, created_at),
    INDEX idx_purchases_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 웹훅 원문 수신함 (추가 제안, 멱등 처리의 기준). body는 처리기가 다시 읽는 원문 (추가 제안)
CREATE TABLE shop_webhook_inbox (
    webhook_id      VARCHAR(80)  NOT NULL PRIMARY KEY,
    type            VARCHAR(64)  NOT NULL,
    tx_id           VARCHAR(80)  NULL,
    body_sha256     CHAR(64)     NOT NULL,
    body            MEDIUMTEXT   NOT NULL,
    received_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    state           VARCHAR(16)  NOT NULL COMMENT 'RECEIVED · DONE · HELD · FAILED · DEAD',
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NULL COMMENT '(추가 제안) 재시도 시각',
    error           TEXT         NULL,
    processed_at    TIMESTAMP(3) NULL,
    INDEX idx_inbox_pending (state, next_attempt_at),
    INDEX idx_inbox_tx (tx_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 게임 안 진주 사용 주문 (추가 제안, 이중 클릭 방지). 버튼마다 만든 order_id(UUID v7)를 참조 ID로 쓴다
CREATE TABLE shop_order (
    order_id   UUID         NOT NULL PRIMARY KEY,
    uuid       UUID         NOT NULL,
    kind       VARCHAR(24)  NOT NULL COMMENT 'COSMETIC · BUNDLE · GIFT · PASS_PREMIUM · PASS_LEVEL · CHEST · NAME_CHANGE · SHARD_EXCHANGE',
    target     VARCHAR(96)  NOT NULL,
    pearls     BIGINT       NOT NULL,
    recipient  UUID         NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_order_target (uuid, kind, target),
    INDEX idx_order_day (uuid, kind, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 코스메틱 영구 소유 (시즌 초기화 대상 아님)
CREATE TABLE cosmetics_owned (
    uuid        UUID         NOT NULL,
    cosmetic_id VARCHAR(96)  NOT NULL,
    source      VARCHAR(24)  NOT NULL COMMENT 'SHOP · BUNDLE · GIFT · PASS · CHEST · SHARD · DONOR_MONTHLY · FESTIVAL · CODEX · ADMIN · FREE',
    ref_id      VARCHAR(64)  NULL COMMENT '(추가 제안) 출처 참조 ID',
    obtained_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, cosmetic_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 장착 상태 (추가 제안). slot = 코스메틱 분류 키
CREATE TABLE cosmetics_equipped (
    uuid        UUID        NOT NULL,
    slot        VARCHAR(24) NOT NULL,
    cosmetic_id VARCHAR(96) NOT NULL,
    PRIMARY KEY (uuid, slot)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 후원 등급 (추가 제안). period_end 뒤 3일 유예 후 ENDED. Proxy·World는 Common DonorTier(Redis harbor:donor)로 조회
CREATE TABLE subscriptions (
    uuid               UUID         NOT NULL PRIMARY KEY,
    tier               VARCHAR(16)  NOT NULL,
    tebex_recurring_id VARCHAR(80)  NULL,
    state              VARCHAR(16)  NOT NULL COMMENT 'ACTIVE · CANCEL_PENDING · ENDED',
    period_end         TIMESTAMP(3) NULL,
    started_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_subscriptions_recurring (tebex_recurring_id),
    INDEX idx_subscriptions_state (state, period_end)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 시즌 패스. premium 0 무료 · 1 프리미엄 · 2 프리미엄+. exp는 누적 경험치(줄지 않음), bought는 진주로 산 단계 (추가 제안)
CREATE TABLE season_pass (
    uuid       UUID         NOT NULL,
    season     INT          NOT NULL,
    level      INT          NOT NULL DEFAULT 0,
    exp        BIGINT       NOT NULL DEFAULT 0,
    premium    TINYINT      NOT NULL DEFAULT 0,
    bought     INT          NOT NULL DEFAULT 0 COMMENT '(추가 제안) 진주 단계 구매 + 프리미엄+ 즉시 단계',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, season),
    INDEX idx_season_pass_level (season, level)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 패스 보상 중복 수령 방지 (추가 제안). track = free · premium · premium_plus (+ _순번)
CREATE TABLE season_pass_claim (
    uuid       UUID         NOT NULL,
    season     INT          NOT NULL,
    level      INT          NOT NULL,
    track      VARCHAR(24)  NOT NULL,
    claimed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, season, level, track)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 보물함 개봉 기록 (추가 제안, 확률 검증). 모든 결과를 표 버전과 함께 남긴다
CREATE TABLE chest_open_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid          UUID         NOT NULL,
    chest_id      VARCHAR(96)  NOT NULL,
    table_version INT          NOT NULL,
    roll          INT          NOT NULL COMMENT '0~999,999',
    result        VARCHAR(96)  NOT NULL,
    grade         VARCHAR(16)  NOT NULL COMMENT '(추가 제안)',
    pity_hit      TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '(추가 제안)',
    duplicate     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '(추가 제안) 중복 → 진주 조각',
    pity_before   INT          NOT NULL,
    order_id      UUID         NOT NULL COMMENT '(추가 제안)',
    created_at    TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_chest_log_chest (chest_id, table_version, created_at),
    INDEX idx_chest_log_player (uuid, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 보물함 천장 카운터 (추가 제안, 시즌 안에서 이월)
CREATE TABLE chest_pity (
    uuid     UUID        NOT NULL,
    season   INT         NOT NULL,
    chest_id VARCHAR(96) NOT NULL,
    count    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, season, chest_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 진주 조각 (추가 제안). 보물함 중복 보상, 보물함 구성품 교환에만 쓴다 (진주로 되바꿀 수 없음)
CREATE TABLE shop_shards (
    uuid   UUID   NOT NULL PRIMARY KEY,
    shards BIGINT NOT NULL DEFAULT 0
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 상점 이용 정지 (추가 제안, 지불 거절 debt 등)
CREATE TABLE shop_flags (
    uuid       UUID         NOT NULL PRIMARY KEY,
    locked     TINYINT(1)   NOT NULL DEFAULT 0,
    reason     VARCHAR(128) NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 미확인 결제·선물 알림 (추가 제안). 오프라인이면 다음 접속 때 보여 준다
CREATE TABLE shop_notice (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid       UUID         NOT NULL,
    kind       VARCHAR(24)  NOT NULL,
    amount     BIGINT       NOT NULL DEFAULT 0,
    detail     VARCHAR(128) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    seen_at    TIMESTAMP(3) NULL,
    INDEX idx_notice_unseen (uuid, seen_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 항구 축제 게이지 (추가 제안)
CREATE TABLE festival_state (
    festival_id VARCHAR(96)  NOT NULL PRIMARY KEY,
    total_krw   BIGINT       NOT NULL DEFAULT 0,
    reached_at  TIMESTAMP(3) NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 축제 후원 기여 기록 (추가 제안, docs 20 12.4: 칭호·명판)
CREATE TABLE festival_contribution (
    tx_id       VARCHAR(80)  NOT NULL PRIMARY KEY,
    uuid        UUID         NOT NULL,
    festival_id VARCHAR(96)  NOT NULL,
    amount_krw  BIGINT       NOT NULL,
    created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_festival_contribution (festival_id, uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 크리에이터 코드 (추가 제안, 시즌 2 이후 기능). 구매자가 등록한 코드와 결제별 정산 기록
CREATE TABLE creator_support (
    uuid   UUID         NOT NULL PRIMARY KEY,
    code   VARCHAR(16)  NOT NULL,
    set_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE creator_payout (
    tx_id      VARCHAR(80)  NOT NULL PRIMARY KEY,
    code       VARCHAR(16)  NOT NULL,
    uuid       UUID         NOT NULL,
    amount_krw BIGINT       NOT NULL,
    share_krw  BIGINT       NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_creator_payout (code, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 닉네임 표시 변경권 ('11. 데이터 설계' 3.2 players.display_name). players는 Common 소유라 열 추가만 한다
ALTER TABLE players ADD COLUMN IF NOT EXISTS display_name VARCHAR(16) NULL;
