-- Harbor-World 소유 테이블 (docs 20 8.3, '11. 데이터 설계' 3.1). 시즌 열이 있는 표는 season/reset_plan.yml에 있다.

-- (추가 제안) 지역 첫 방문: 레시피 '방문' 해금, 비행선 노선, 탈것 첫 진입 조건. 시즌 초기화
CREATE TABLE region_visit (
    uuid      UUID        NOT NULL,
    season    INT         NOT NULL,
    region_id VARCHAR(96) NOT NULL,
    first_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid, season, region_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 해도 비트맵 (지역 경계 상자 기준 청크 비트). claimed = 받은 보상 단계 비트 (추가 열). 지연 쓰기, 시즌 초기화
CREATE TABLE sea_chart (
    uuid       UUID        NOT NULL,
    season     INT         NOT NULL,
    region_id  VARCHAR(96) NOT NULL,
    bits       MEDIUMBLOB  NOT NULL,
    visited    INT         NOT NULL DEFAULT 0,
    claimed    INT         NOT NULL DEFAULT 0,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (uuid, season, region_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 보물 지도 수령: 지도 uid 유니크로 같은 지도 두 번 수령을 막는다 (원장 TREASURE와 같은 트랜잭션). 시즌 초기화
CREATE TABLE treasure_claim (
    map_uid    UUID        NOT NULL PRIMARY KEY,
    uuid       UUID        NOT NULL,
    season     INT         NOT NULL,
    region_id  VARCHAR(96) NOT NULL,
    site_id    VARCHAR(64) NOT NULL,
    tier       VARCHAR(32) NOT NULL,
    gold       BIGINT      NOT NULL DEFAULT 0,
    claimed_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_treasure_claim_uuid (uuid, season)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 선박. state = STORED · AFLOAT · WRECKED. upgrades = {"speed":1,"cargo":0,"hull":2}. 시즌 초기화
CREATE TABLE ships (
    ship_id    BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner      UUID        NOT NULL,
    season     INT         NOT NULL,
    type       VARCHAR(96) NOT NULL,
    upgrades   TEXT        NOT NULL,
    durability INT         NOT NULL,
    state      VARCHAR(8)  NOT NULL DEFAULT 'STORED',
    cargo      MEDIUMBLOB  NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_ships_type (owner, season, type),
    INDEX idx_ships_owner (owner, season)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 해상 이벤트 실행 기록: 재시작 때 진행 중 이벤트 복구, 최소 간격·시즌당 횟수 판정. state = WARN · ACTIVE · ENDED · CANCELLED. 이월
-- run_season은 시즌당 횟수 계산용 (기록 보관 표라 시즌 초기화 대상이 아니다)
CREATE TABLE sea_event_log (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    run_id     VARCHAR(96) NOT NULL,
    event_id   VARCHAR(96) NOT NULL,
    region     VARCHAR(96) NULL,
    run_season INT         NOT NULL,
    warn_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ends_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    state      VARCHAR(12) NOT NULL,
    manual     BOOLEAN     NOT NULL DEFAULT FALSE,
    UNIQUE KEY uq_sea_event_run (run_id),
    INDEX idx_sea_event_state (state, ends_at),
    INDEX idx_sea_event_event (event_id, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 하우징 플롯 ('11. 데이터 설계' 3장 housing_plot + 3.2 grid_x·grid_z·spawn). plot_id = 나선 순서 번호. 이월
-- redstone = 플롯 안 레드스톤 부품 수 (추가 열, 상한 검사용)
CREATE TABLE housing_plot (
    plot_id      BIGINT      NOT NULL PRIMARY KEY,
    owner        UUID        NOT NULL,
    size         INT         NOT NULL,
    theme        VARCHAR(64) NOT NULL DEFAULT 'default',
    visit_policy VARCHAR(8)  NOT NULL DEFAULT 'PUBLIC',
    likes        INT         NOT NULL DEFAULT 0,
    grid_x       INT         NOT NULL,
    grid_z       INT         NOT NULL,
    spawn_x      DOUBLE      NOT NULL,
    spawn_y      DOUBLE      NOT NULL,
    spawn_z      DOUBLE      NOT NULL,
    spawn_yaw    FLOAT       NOT NULL DEFAULT 0,
    redstone     INT         NOT NULL DEFAULT 0,
    created_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_housing_owner (owner),
    UNIQUE KEY uq_housing_grid (grid_x, grid_z)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 공동 건축자. role = BUILDER. '친구만' 방문 정책도 이 목록으로 본다 (열린 질문). 이월
CREATE TABLE housing_member (
    plot_id  BIGINT      NOT NULL,
    uuid     UUID        NOT NULL,
    role     VARCHAR(12) NOT NULL DEFAULT 'BUILDER',
    added_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (plot_id, uuid),
    INDEX idx_housing_member_uuid (uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 좋아요: 방문객 1명이 플롯 1곳에 하루 1회. 이월
CREATE TABLE housing_like (
    plot_id BIGINT NOT NULL,
    uuid    UUID   NOT NULL,
    day     DATE   NOT NULL,
    PRIMARY KEY (plot_id, uuid, day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 산 장식 개수 (시즌마다 보유 아이템이 초기화돼도 장식은 남는다). 이월
CREATE TABLE housing_decor_owned (
    uuid     UUID        NOT NULL,
    decor_id VARCHAR(96) NOT NULL,
    count    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, decor_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 개인 어항 수조 위치 (DisplayAnchor). 전시 칸 데이터(housing_display)는 Collection이 갖는다. 이월
CREATE TABLE housing_anchor (
    plot_id  BIGINT      NOT NULL,
    slot     INT         NOT NULL,
    decor_id VARCHAR(96) NOT NULL,
    world    VARCHAR(64) NOT NULL,
    x        INT         NOT NULL,
    y        INT         NOT NULL,
    z        INT         NOT NULL,
    facing   VARCHAR(8)  NOT NULL DEFAULT 'NORTH',
    PRIMARY KEY (plot_id, slot),
    UNIQUE KEY uq_housing_anchor_pos (world, x, y, z)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 주간 낚시 대회 점수 (Season RankingService 원천 tournament). 시즌 초기화
CREATE TABLE tournament_score (
    run_id     VARCHAR(96) NOT NULL,
    uuid       UUID        NOT NULL,
    season     INT         NOT NULL,
    name       VARCHAR(32) NOT NULL,
    score      INT         NOT NULL DEFAULT 0,
    catches    INT         NOT NULL DEFAULT 0,
    rewarded   BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, uuid),
    INDEX idx_tournament_rank (season, run_id, score)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 서버 이동·재접속에 이어 가는 플레이어 상태: 환경 디버프 게이지, 마지막 안전 위치. 이월 (값이 작아 정리하지 않는다)
CREATE TABLE world_player_state (
    uuid       UUID        NOT NULL PRIMARY KEY,
    cold       DOUBLE      NOT NULL DEFAULT 0,
    oxygen     DOUBLE      NOT NULL DEFAULT -1,
    safe_world VARCHAR(64) NULL,
    safe_x     DOUBLE      NOT NULL DEFAULT 0,
    safe_y     DOUBLE      NOT NULL DEFAULT 0,
    safe_z     DOUBLE      NOT NULL DEFAULT 0,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
