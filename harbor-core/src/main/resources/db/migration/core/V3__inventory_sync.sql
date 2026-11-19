-- 인벤토리 동기화 (추가 제안). 서버 간 같은 인벤토리·엔더 상자·PDC·경험치·체력·배고픔·효과를 쓴다 (HuskSync 방식 참고).
-- data = Core InventoryData 형식 (format 번호로 구분). 아이템은 Paper 직렬화라 버전이 바뀌어도 서버가 변환한다.
CREATE TABLE player_inventory (
    uuid       UUID         NOT NULL PRIMARY KEY,
    format     SMALLINT     NOT NULL,
    data       MEDIUMBLOB   NOT NULL,
    server     VARCHAR(32)  NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 스냅숏 기록 (퇴장·이동·사망·복구 때, 플레이어마다 최근 balance.yml inventory.history_keep개)
CREATE TABLE player_inventory_history (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    uuid       UUID         NOT NULL,
    reason     VARCHAR(16)  NOT NULL,
    server     VARCHAR(32)  NOT NULL,
    format     SMALLINT     NOT NULL,
    data       MEDIUMBLOB   NOT NULL,
    hash       CHAR(64)     NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_inventory_history (uuid, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
