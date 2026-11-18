-- 개인 어항 전시 ('11. 데이터 설계' 3장 housing_display, docs 20 4.3). 이월 (시즌 열 없음, reset_plan: scope full · keep).
-- 아이템이 아니라 누적 도감 기록을 전시한다. quality · first_caught · theme · owner · updated_at은 추가 제안 열.
-- theme: 이 칸의 어항 테마 코스메틱 (NULL = 기본 테마, D14: 테마 칸 수를 넘는 칸은 기본으로 되돌린다)
CREATE TABLE housing_display (
    plot_id      BIGINT        NOT NULL,
    slot         INT           NOT NULL,
    fish_id      VARCHAR(96)   NOT NULL,
    size         DECIMAL(7, 1) NOT NULL,
    quality      TINYINT       NOT NULL DEFAULT 1,
    first_caught TIMESTAMP(3)  NULL,
    theme        VARCHAR(96)   NULL,
    owner        UUID          NOT NULL,
    updated_at   TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (plot_id, slot),
    INDEX idx_housing_display_owner (owner)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- (추가 제안) 방류 기록 (감사·통계). 시즌별 행, reset_plan: archive_keep
CREATE TABLE release_log (
    id          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    season      INT           NOT NULL,
    uuid        UUID          NOT NULL,
    fish_id     VARCHAR(96)   NOT NULL,
    size        DECIMAL(7, 1) NOT NULL,
    quality     TINYINT       NOT NULL,
    item_uuid   UUID          NULL,
    released_at TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_release_log_player (season, uuid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
