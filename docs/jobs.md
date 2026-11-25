# Harbor-Jobs

직업 단계·계열·레벨·경험치·스킬 트리·물비늘 사용을 맡는다. 결과는 Core 보정자로 올리고, 다른 모듈은 `JobsService`만 쓴다.

## 주요 기능

- **레벨과 경험치:** 낚시 등으로 경험치를 얻어 최대 Lv.70까지 오른다. 필요 경험치는 `2 × L^2.05`(10 단위 반올림)이다. 직업 단계마다 레벨 상한이 있다 (1차 30, 2차 60, 3차 70). 레벨이 오를 때마다 물비늘 1개를 받고, 시즌 첫 접속 때도 1개를 받는다 (원장 참조 ID `lvl:<시즌>:<uuid>:1`, 레벨업 69회와 합쳐 시즌 70개). 같은 참조 ID라 서버를 옮기거나 다시 접속해도 한 번만 나간다.
- **직업 단계(차수)와 계열:** 모두 1차 "견습 어부"로 시작한다. 2차 전직 때 계열 네 개 중 하나를 고른다.

  | 계열 | 2차 | 3차 | 자동 패시브 (예) |
  |---|---|---|---|
  | 탐험 `explore` | 원양 탐험가 | 바다의 개척자 | 보트 속도, 폭풍 피해 감소, 혹한·화염·산소 디버프 시간 감소 |
  | 수집 `collect` | 희귀어 추적자 | 전설의 강태공 | 희귀·영웅 가중치 |
  | 생산 `produce` | 통발 장인 | 심해 명인 | 통발 슬롯·설치 개수 |
  | 경제 `economy` | 어시장 중개인 | 항구 거상 | 판매가, 경매 수수료 감소 |

- **전직:** 전직 퀘스트 보고 단계에서 확인 화면이 뜨고 골드를 낸다 (2차 15,000G, 3차 90,000G). 2차는 계열 선택이 먼저 나온다. NPC 모듈이 있으면 전직 컷씬이 재생된다.
- **품질 상한:** 직업 단계에 따라 낚을 수 있는 최고 품질이 정해진다 (1차 ★3, 2차 ★4, 3차 ★5). 전직 후 처음 ★4(2차)·★5(3차)를 낚으면 물비늘을 받는다.
- **스킬 트리 (`/스킬`):** 물비늘을 투자해 스킬을 배운다. 공통 트리와 계열 트리(2차·3차)와 캡스톤이 있다. 2단·3단은 투자 포인트(8·30)가 차야 열린다. 다른 계열 트리에는 `other_line_limit`까지만 투자할 수 있다.
- **계열 변경·스킬 초기화:** 길드 마스터 NPC 대화에서 한다.
  - 계열 변경: 2차 30,000G·3차 80,000G. 계열 트리(2차·3차·캡스톤·다른 계열) 투자분의 80%(`refund_ratio`, 내림)를 돌려받고 공통 트리는 유지한다. 바꾼 뒤 7일(`line_change_cooldown_days`) 동안은 다시 바꿀 수 없다 (`jobs_state.line_changed_at` 기준, 관리자 `/harbor jobs tier`는 쿨타임과 무관).
  - 공통 트리 초기화: 시즌 첫 1회 무료, 이후 5,000G. 공통 트리 투자분을 100% 돌려받는다.
- **사라진 스킬 노드:** `skill_tree.yml`에서 노드가 빠지면 그 노드에 투자한 물비늘을 다음 접속 때 전액 돌려받고 행을 지운다 (사유 `SKILL_REFUND`, 참조 ID `removed:<시즌>:<노드>:<uuid>`). 금액은 `skill_alloc.invested`(투자 때마다 실제 낸 물비늘 누계)다. 이 열이 생기기 전(V2 마이그레이션 이전)의 투자분은 0으로 남아 있어 환급하지 못하고 경고 로그만 남긴다 (관리자가 `/harbor currency`로 처리).
- **캡스톤:** 계열마다 하나다. 예: "미개척 해역"은 반경 64블록 영역 효과(10분, 쿨타임 2시간, 영역 안의 사용자와 그 파티원만 받음. 파티가 없으면 본인만), "큰손 거래"는 주 1회 판매 보너스. 쿨타임은 즉시 저장하므로 재접속이나 서버 이동으로 지워지지 않는다.
- **지역 감쇠:** 내 레벨보다 15 이상 낮은 지역에서는 경험치가 절반이다.
- **캐치업:** 매일 한 서버가 최근 7일 접속자의 레벨 중앙값을 계산한다. 시즌 5주차부터 중앙값보다 10 이상 낮으면 경험치 +50%, 9주차부터 +100%.

## 플레이어 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/직업` | (없음) | 계열·단계·레벨·경험치·품질 상한·다음 전직 조건·캐치업 중앙값 |
| `/스킬` | (없음) | 스킬 트리 GUI (투자) |

## 관리자 명령

`/harbor jobs …`는 `harbor.admin.jobs`가 필요하다.

| 명령 | 설명 |
|---|---|
| `/harbor jobs info <플레이어>` | 계열·단계·레벨·경험치·스킬·캡스톤 |
| `/harbor jobs exp <플레이어> <양>` | 경험치 지급 |
| `/harbor jobs level <플레이어> <1~70>` | 레벨 설정 |
| `/harbor jobs tier <플레이어> <1~3> [계열]` | 단계 설정 (2·3차는 계열 지정) |
| `/harbor jobs reset <플레이어> <common\|line\|all> [환급 true/false]` | 스킬 초기화 |
| `/harbor jobs cooldown <플레이어> clear` | 캡스톤 쿨타임 지우기 |
| `/harbor jobs median` · `median recalc` | 캐치업 중앙값 보기·다시 계산 |

## 콘텐츠 파일

| 파일 | 내용 | 주요 필드 |
|---|---|---|
| `jobs.yml` | 계열 정의 | `id`(job/…) `line` `names`(단계별 이름) `passives`(`tier` `modifier` `value`) `capstone`(`node` `item` `duration_s` `cooldown_s` `radius` `weekly_uses`) |
| `skill_tree.yml` | 스킬 노드 | `id`(skill/…) `name` `tree`(common·계열) `tier` `max_rank` `cost` `effects`(`modifier` `value_per_rank`) |

- 수치가 아닌 효과는 값 1의 `feature_` 스탯으로 둔다 (예: `feature_storm_sailing`).
- 레벨 곡선이 `jobs.curve_check` 표와 다르면 리로드를 거부한다. 계열 트리 노드와 캡스톤 정의가 서로 맞는지도 검사한다.

## 설정 값

`balance.yml` `jobs`:

| 키 | 기본값 | 뜻 |
|---|---|---|
| `exp_formula` | `{coef: 2, exp: 2.05, round: 10}` | 레벨업 필요 경험치 |
| `max_level` · `tier_level_cap` | 70 · `{1: 30, 2: 60, 3: 70}` | 레벨 상한 |
| `quality_cap` | `{1: 3, 2: 4, 3: 5}` | 단계별 품질 상한 |
| `advance_cost` | `{2: 15000, 3: 90000}` | 전직 골드 |
| `line_change_cost` | `{2: 30000, 3: 80000}` | 계열 변경 골드 |
| `refund_ratio` · `common_reset_cost` | 0.8 · 5000 | 계열 변경 환급 비율, 공통 트리 초기화 비용 |
| `line_change_cooldown_days` | 7 | 계열 변경 쿨타임 (제안값) |
| `region_decay` | `{gap: 15, mult: 0.5}` | 지역 감쇠 |
| `catchup` | 5주차 +0.5, 9주차 +1.0 (중앙값 −10) | 캐치업 |
| `tier_first_quality_bonus` | 2 | 전직 후 첫 상한 품질 물비늘 (제안값) |
| `tier_points` · `capstone_requires` · `other_line_limit` | `{2: 8, 3: 30}` · 60 · 40 | 트리 잠금 |
| `curve_check` | 레벨별 누적 경험치 표 | 곡선 검사 |

`stats` 절의 `exp_gain`, `boat_speed`, `trap_slots` 같은 보정자 스탯 범위도 영향을 준다.

## 다른 모듈과의 연결

- **제공 서비스:** `JobsService`: `state`, `qualityCap`, `grantExp`(감쇠·캐치업·`exp_gain` 적용), `requestAdvance`(Quest의 전직 보고), `claimTierFirstQuality`(Fishing이 포획 때), `capstone`, `consumeWeeklyUse`(Economy 큰손 거래). 스킬: `skillNodes`·`skillNode`(`SkillNodeInfo`), `skills`(보유 등급), `skillPoints`(물비늘), `checkInvest`·`invest`(스킬 GUI와 같은 검사·원장, `InvestResult`), `resetCommonSkills`·`changeLine`(NPC 대화와 같은 비용). 관리: `adminResetSkills`·`adminSetTier`·`adminSetLevel`(`/harbor jobs reset|tier|level`과 같은 경로)

- **이벤트:** `JobExpGainEvent`, `JobLevelUpEvent`, `JobAdvancedEvent`, `JobLineChangedEvent`, `SkillAllocatedEvent`, `SkillResetEvent`, `CapstoneUsedEvent`
- **원장 사유:** `LEVEL_UP`, `JOB_ADVANCE`, `LINE_CHANGE`, `SKILL_INVEST`, `SKILL_REFUND`, `SKILL_RESET`, `TIER_FIRST_QUALITY`
- **NPC (선택):** 대화 액션 `job:change_line`(계열 변경), `job:reset_skill`(공통 트리 초기화), 전직 컷씬(`cutscene/job_advance`). Harbor-Npc가 없으면 이 연결만 빠진다.

## 주의 사항

- 캐치업 중앙값은 분산 잠금을 잡은 한 서버만 계산한다.
- `jobs_state.join_bonus_given`은 시즌 첫 접속 물비늘을 접속마다 원장에 다시 쓰지 않게 하는 표시다. 실제 중복 방지는 원장 참조 ID가 한다.
- 패시브·캡스톤 수치 중 문서에 없던 값은 YAML에 제안값으로 표시되어 있다.
