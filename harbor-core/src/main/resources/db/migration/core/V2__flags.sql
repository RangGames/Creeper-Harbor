-- 진행 플래그 (docs 20 0.2 FlagService, 추가 제안). season = 0이면 영구 범위.
CREATE TABLE player_flags (
    uuid   UUID         NOT NULL,
    season INT          NOT NULL,
    flag   VARCHAR(96)  NOT NULL,
    value  VARCHAR(255) NOT NULL DEFAULT '1',
    set_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (uuid, season, flag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE server_flags (
    flag   VARCHAR(96)  NOT NULL PRIMARY KEY,
    value  VARCHAR(255) NOT NULL DEFAULT '1',
    season INT          NOT NULL DEFAULT 0 COMMENT '0 = 영구, 그 밖 = 그 시즌에만 유효',
    set_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
