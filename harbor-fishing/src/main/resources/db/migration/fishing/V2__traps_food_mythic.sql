-- 통발 (docs 14 3장 traps + 3.2 추가 열, docs 20 3.3·3.4). 시즌 초기화 대상 (Season reset_plan).
-- 설치·계산·수확·회수는 즉시 트랜잭션. 계산은 last_calc 조건부 갱신으로 두 번 계산을 막는다.
CREATE TABLE traps (
    id                 BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner              UUID         NOT NULL,
    season             INT          NOT NULL,
    region             VARCHAR(96)  NOT NULL,
    world              VARCHAR(64)  NOT NULL COMMENT '(추가 제안) 블록 위치 월드',
    x                  INT          NOT NULL,
    y                  INT          NOT NULL,
    z                  INT          NOT NULL,
    tier               INT          NOT NULL COMMENT '통발 단계 (나무 1 · 철 2 · 산호 3 · 심해 4)',
    item_id            VARCHAR(96)  NOT NULL COMMENT '(추가 제안) 설치한 통발 아이템',
    slots              INT          NOT NULL COMMENT '통발 기본 슬롯',
    extra_slots        INT          NOT NULL DEFAULT 0 COMMENT '(추가 제안) 구매한 확장 슬롯',
    bait_count         INT          NOT NULL DEFAULT 0,
    last_calc          TIMESTAMP(3) NOT NULL,
    contents           MEDIUMTEXT   NOT NULL COMMENT '(추가 제안) 미수확 어획 JSON',
    value_24h          DOUBLE       NOT NULL DEFAULT 0 COMMENT '(추가 제안) 수익 상한 창 누적 가치',
    value_window_start TIMESTAMP(3) NOT NULL COMMENT '(추가 제안) 수익 상한 창 시작',
    last_harvest       TIMESTAMP(3) NOT NULL COMMENT '(추가 제안) 7일 자동 회수 기준',
    placed_at          TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uq_traps_spot (season, world, x, z),
    INDEX idx_traps_owner (owner, season),
    INDEX idx_traps_harvest (season, last_harvest)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 요리 버프 1종 (출처 food:, 재접속·서버 이동 뒤에도 남은 시간만큼 다시 건다). 이월 안 함
CREATE TABLE fishing_food (
    uuid       UUID         NOT NULL PRIMARY KEY,
    item_id    VARCHAR(96)  NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 신화 서버 마릿수 카운터. 문서의 Redis 키 harbor:mythic:<이벤트>:<어종>을 대신한다
-- (Core가 Redis 원자 연산을 열어 주면 그쪽으로 옮김). event_id는 이벤트 실행마다 다르다
CREATE TABLE fishing_mythic_counter (
    event_id VARCHAR(64) NOT NULL,
    fish_id  VARCHAR(96) NOT NULL,
    count    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, fish_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
