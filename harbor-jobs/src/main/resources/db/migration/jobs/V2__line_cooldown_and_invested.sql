-- (추가 제안) docs 20 2.3 jobs_state.line_changed_at: 계열 변경 7일 쿨타임 기준 시각
ALTER TABLE jobs_state ADD COLUMN line_changed_at TIMESTAMP(3) NULL;
-- (추가 제안) 시즌 첫 접속 물비늘 지급 여부. 원장 참조 ID(lvl:<시즌>:<uuid>:1)가 중복 지급을 막고, 이 열은 접속마다 원장을 두드리지 않게 한다
ALTER TABLE jobs_state ADD COLUMN join_bonus_given BOOLEAN NOT NULL DEFAULT FALSE;
-- (추가 제안) 노드별 실제 투자 물비늘 누계. 콘텐츠에서 사라진 노드를 전액 환급할 때 쓴다 (이 열 이전 투자분은 0)
ALTER TABLE skill_alloc ADD COLUMN invested BIGINT NOT NULL DEFAULT 0;
