-- Harbor-Boss 소유 테이블 (docs 20 10.3, 모두 추가 제안).
-- season 열이 있는 표는 content/season/reset_plan.yml에 있다:
--   boss_encounter · boss_participation · boss_pity = archive_reset, boss_records = keep (이월)
-- boss_reward_pending · boss_profile은 season 열이 없고 손대지 않는다 (미지급 보상은 시즌을 넘겨도 지급).

-- 조우 감사·복구용. 시작·종료 행만 비동기로 쓴다.
CREATE TABLE boss_encounter (
    encounter_id UUID          NOT NULL,
    boss_id      VARCHAR(96)   NOT NULL,
    season       INT           NOT NULL,
    week         INT           NOT NULL,
    server       VARCHAR(32)   NOT NULL,
    arena_slot   INT           NOT NULL,
    party_size   INT           NOT NULL,
    state        VARCHAR(16)   NOT NULL,   -- RUNNING · CLEARED · FAILED · ABORTED
    started_at   TIMESTAMP(3)  NOT NULL,
    ended_at     TIMESTAMP(3)  NULL,
    duration_ms  BIGINT        NULL,
    -- (추가 제안) 서버 이상 시 미끼 환급 근거: 소환자와 차감한 미끼
    leader       UUID          NULL,
    summon_item  VARCHAR(96)   NULL,
    -- (추가 제안) 드롭 시드 (조우마다 기록, docs 20 10.4 2단계)
    seed         BIGINT        NOT NULL,
    -- (추가 제안) 월드 보스 진행률 (남은 비율 0~1, 10초마다)
    progress     DOUBLE        NULL,
    PRIMARY KEY (encounter_id),
    INDEX idx_boss_encounter_server (server, state),
    INDEX idx_boss_encounter_season (season)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 기여도 원자료. season은 reset_plan(archive_reset) 조건용 (추가 제안).
CREATE TABLE boss_participation (
    encounter_id UUID          NOT NULL,
    uuid         UUID          NOT NULL,
    season       INT           NOT NULL,
    role         VARCHAR(16)   NOT NULL,
    damage       DOUBLE        NOT NULL DEFAULT 0,
    tension      DOUBLE        NOT NULL DEFAULT 0,
    repair       DOUBLE        NOT NULL DEFAULT 0,
    evasion      DOUBLE        NOT NULL DEFAULT 0,
    score        DOUBLE        NOT NULL DEFAULT 0,
    `rank`       INT           NOT NULL DEFAULT 0,
    rewarded     TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (encounter_id, uuid),
    INDEX idx_boss_participation_season (season),
    INDEX idx_boss_participation_uuid (uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 보스별 최단 기록 (이월). 시즌별 1~10위만 남긴다. members = UUID 문자열 JSON 배열 (파티장 먼저).
-- Season이 SQL로 읽는다 (랭킹 boss_records 원천, 명예의 전당 확정).
CREATE TABLE boss_records (
    encounter_id UUID          NOT NULL,
    boss_id      VARCHAR(96)   NOT NULL,
    season       INT           NOT NULL,
    duration_ms  BIGINT        NOT NULL,
    members      TEXT          NOT NULL,
    cleared_at   TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (encounter_id),
    INDEX idx_boss_records_rank (boss_id, season, duration_ms)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 미지급 보상 (인벤토리 부족·오프라인·서버 이상). id는 (조우, 플레이어, 종류)로 정해지는 이름 기반 UUID라
-- 같은 보상을 두 번 넣으려 하면 PK 충돌로 트랜잭션이 취소된다 (중복 지급 0건).
CREATE TABLE boss_reward_pending (
    id           UUID          NOT NULL,
    encounter_id UUID          NOT NULL,
    uuid         UUID          NOT NULL,
    payload      TEXT          NOT NULL,
    created_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    delivered_at TIMESTAMP(3)  NULL,
    PRIMARY KEY (id),
    INDEX idx_boss_pending_uuid (uuid, delivered_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 레이드 천장 카운터 (드롭별).
CREATE TABLE boss_pity (
    uuid    UUID         NOT NULL,
    season  INT          NOT NULL,
    boss_id VARCHAR(96)  NOT NULL,
    drop_id VARCHAR(96)  NOT NULL,
    count   INT          NOT NULL,
    PRIMARY KEY (uuid, season, boss_id, drop_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) PlayerComponent<BossProfile>: 선호 역할, 인스턴스로 가기 전 위치 (돌아올 곳)
CREATE TABLE boss_profile (
    uuid           UUID         NOT NULL,
    preferred_role VARCHAR(16)  NULL,
    return_server  VARCHAR(32)  NULL,
    return_world   VARCHAR(64)  NULL,
    return_x       DOUBLE       NULL,
    return_y       DOUBLE       NULL,
    return_z       DOUBLE       NULL,
    return_yaw     FLOAT        NULL,
    return_pitch   FLOAT        NULL,
    PRIMARY KEY (uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
