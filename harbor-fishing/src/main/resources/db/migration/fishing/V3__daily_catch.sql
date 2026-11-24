-- (추가 제안) 오늘의 어획 요약 ('4. 낚시 연출 & 보스전' 1.2 입항 요약, 1.3 바로가기). 하루 한 행, 시즌과 무관
CREATE TABLE fishing_daily (
    uuid       UUID         NOT NULL,
    day        DATE         NOT NULL,
    catches    INT          NOT NULL DEFAULT 0,
    best_grade VARCHAR(16)  NULL,
    big_fish   VARCHAR(96)  NULL,
    big_cm     DOUBLE       NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, day)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
