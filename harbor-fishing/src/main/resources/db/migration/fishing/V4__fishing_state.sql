-- (추가 제안) 플레이어별 낚시 상태 (docs 20 3.3 FishingData.missedBig). 시즌 전환 때 초기화 대상
-- missed_big: 직업 품질 상한에 걸려 '놓친 대어'가 된 횟수 ('1. 기획서 본문' 3.2, 길드 마스터가 전직 권유 때 언급)
CREATE TABLE fishing_state (
    uuid       UUID NOT NULL,
    season     INT  NOT NULL,
    missed_big INT  NOT NULL DEFAULT 0,
    PRIMARY KEY (uuid, season)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
