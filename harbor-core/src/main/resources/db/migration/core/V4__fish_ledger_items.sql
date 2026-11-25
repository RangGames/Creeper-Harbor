-- 고유 ID 장부에 보스 장비 기록 ('11. 데이터 설계' 3.2 fish_ledger.item_id).
-- 보스 장비 행은 item_id만 채우고 fish_id·size·quality는 비운다. 열 추가와 NOT NULL 완화만 한다 (기존 행은 그대로).
-- state = ACTIVE · IN_AUCTION · SOLD · DONATED · RELEASED · GIFTED · ARCHIVED (+ 코드에서 쓰는 LISTED · DESTROYED)
ALTER TABLE fish_ledger
    ADD COLUMN IF NOT EXISTS item_id VARCHAR(96) NULL AFTER fish_id,
    MODIFY COLUMN fish_id VARCHAR(96)   NULL,
    MODIFY COLUMN size    DECIMAL(7, 1) NULL,
    MODIFY COLUMN quality TINYINT       NULL,
    MODIFY COLUMN state   VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE · IN_AUCTION · LISTED · SOLD · DONATED · RELEASED · GIFTED · DESTROYED · ARCHIVED';
