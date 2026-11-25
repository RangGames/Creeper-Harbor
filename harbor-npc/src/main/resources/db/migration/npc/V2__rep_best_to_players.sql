-- 최고 평판 등급을 Common players.best_rep_tier로 옮긴다 ('11. 데이터 설계' 3.2). npc_rep_best는 지우지 않고
-- players 행이 없을 때만 쓰는 예비 기록으로 남긴다 (운영 중 스키마는 추가만).
UPDATE players p
    JOIN npc_rep_best b ON b.uuid = p.uuid
SET p.best_rep_tier = GREATEST(p.best_rep_tier, b.best_tier);
