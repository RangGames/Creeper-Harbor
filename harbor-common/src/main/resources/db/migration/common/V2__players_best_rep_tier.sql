-- 시즌 간 최고 평판 단계 ('11. 데이터 설계' 3.2 players.best_rep_tier, D11). 이월 (시즌 전환 때 지우지 않음).
-- 하우징 크기·어항 칸은 이 값으로 판정한다. NPC가 임시로 쓰던 npc_rep_best의 값은 NPC 마이그레이션이 옮긴다.
ALTER TABLE players ADD COLUMN IF NOT EXISTS best_rep_tier INT NOT NULL DEFAULT 1;
