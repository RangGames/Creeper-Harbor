# Harbor-Season

시즌 시계·주간 제한·칭호·랭킹·명예의 전당·시즌 전환을 맡는다. 코드 의존은 Core뿐이다. 다른 모듈과는 `season/reset_plan.yml`(데이터)과 api 패키지(`SeasonHook`, `HallOfFameService.submit` 등)로만 연결하고, 다른 모듈 이벤트는 구독하지 않는다.

## 주요 기능

- **시즌 시계:** 시즌 번호·주차·일일/주간 초기화를 계산한다. 주차는 `floor((지금 − 시즌 시작 − 주차 보정) ÷ 7일) + 1`이다. 1분마다 주차 변경과 초기화 시각을 확인하고 이벤트를 낸다.
- **새 시즌 첫 접속 안내:** 새 시즌에 처음 들어오면 안내 Dialog를 띄운다.
- **주간 제한:** 모듈이 쓰는 주간 횟수 제한(예: 보스 주간 보상 횟수)을 계정별로 센다.
- **칭호:** 칭호를 모으고 하나를 장착한다. 칭호는 도감·보스·명예의 전당·연대기·후원 등에서 받는다. 상태형 칭호(`permanent: false`)는 조건이 끝나면 회수될 수 있다.
- **랭킹:** 한 서버가 보드별 주기(기본 10분)마다 상위 N명을 집계해 모든 서버에 보낸다. 동점이면 먼저 달성한 사람이 앞선다.
  - 원천: `level`(시즌 레벨), `collection_season`(시즌 도감), `records`(어종 최대어), `boss_records`, 그리고 다른 모듈이 등록한 원천.
  - 리그: `all` · `tier1` · `tier2`. 기록 시점의 직업 단계로 정하고, 이후 전직해도 그 기록의 리그는 바뀌지 않는다.
- **명예의 전당:** 시즌 진행 중에는 잠정 기록이다. 시즌 전환 SNAPSHOT 단계에서 아카이브 데이터로 다시 계산해 확정하고 칭호를 준다.
  - 원천: `records` `boss_records` `level_first` `collection_first` `submitted`
  - 규칙: `top1`(대상별 1위) · `first_n`(선착순) · `all`
- **시즌 전환 (10단계):**

  | 단계 | 내용 |
  |---|---|
  | 1 PRECHECK | 다음 시즌 콘텐츠·리소스팩 검사, 디스크·예상 시간 확인 |
  | 2 NOTICE | 공지 |
  | 3 FREEZE | 모든 서버가 플레이어를 내보내고 지연 쓰기 큐를 비운다 |
  | 4 VERIFY | 검증 |
  | 5 BACKUP | 백업 (운영자 확인 `confirm-backup`) |
  | 6 ARCHIVE | `reset_plan.yml`대로 테이블을 `archive_s<n>` 스키마에 복사 |
  | 7 SNAPSHOT | 랭킹·명예의 전당 확정 |
  | 8 RESET | 시즌 n 행 삭제, 재화를 `SEASON_RESET` 원장으로 0. 원본을 지우는 유일한 단계 |
  | 9 CONTENT | 다음 시즌 콘텐츠 |
  | 10 OPEN | 열기 (예: CBT 참여자 기념 칭호) |

  - 모든 단계는 다시 실행해도 결과가 같다. 단계마다 `season_transition_log`에 남기고, 서버가 죽으면 `resume`으로 마지막 완료 단계 다음부터 잇는다.
  - 분산 잠금으로 두 서버가 동시에 전환하지 못한다.
  - RESET 전에는 `rollback`(롤백 A)으로 되돌린다. RESET 뒤에는 5단계 백업을 운영자가 복원한다.

## 플레이어 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/시즌` | `harbor.season` | 현재 시즌·주차·다음 초기화 |
| `/랭킹 [보드] [리그]` | `harbor.season` | 랭킹 |
| `/명예의전당 [시즌]` | `harbor.season` | 명예의 전당 |
| `/칭호` | `harbor.season` | 칭호 GUI |
| `/칭호 목록` · `장착 <칭호>` · `해제` | `harbor.season` | 칭호 목록·장착·해제 |

## 관리자 명령

`/harbor season …`는 `harbor.admin.season`과 아래 하위 권한이 필요하다.

| 명령 | 권한 | 설명 |
|---|---|---|
| `/harbor season info` | | 시즌 상태 |
| `/harbor season transition dry-run` | `harbor.admin.season.transition` | 실제 쓰기 없이 PRECHECK와 테이블별 아카이브·삭제 예정 행 수, 예상 시간 |
| `/harbor season transition start` · `confirm-backup` · `resume` · `rollback` · `status` | `harbor.admin.season.transition` | 전환 시작, 백업 확인, 이어 하기, 롤백 A, 진행 상태 |
| `/harbor season week shift <시간>` | `harbor.admin.season.week` | 주차 보정 (시험용) |
| `/harbor season ranking refresh` | `harbor.admin.season.ranking` | 랭킹 즉시 집계 |
| `/harbor season title grant\|revoke <플레이어> <칭호>` | `harbor.admin.season.title` | 칭호 지급·회수 |
| `/harbor season limit show <플레이어>` · `reset <플레이어> <키>` | `harbor.admin.season.limit` | 주간 제한 보기·초기화 |

전환 진행 상황은 접속 중인 `harbor.admin.season.transition` 보유자와 콘솔에 보낸다.

## 콘텐츠 파일

| 파일 | 내용 | 주요 필드 |
|---|---|---|
| `season/seasons.yml` | 시즌 정의 | `season` `name` `starts_at` `weeks` `theme_region` |
| `season/reset_plan.yml` | 전환 계획 | `currencies` `fish_ledger.archive_states` `open_titles` `tables`(`table` `policy`: archive_reset·archive_keep·keep·custom, `scope`: season_column·full, `column`) |
| `season/rankings.yml` | 랭킹 보드 | `boards`(`id` `name` `source` `subject` `leagues` `size` `refresh`) |
| `season/hall_of_fame.yml` | 명예의 전당 | `categories`(`category` `name` `source` `rule` `order` `unit` `count` `title`) |
| `titles.yml` | 칭호 | `id`(title/…) `name` `style`(common·rare·epic·legendary·special) `permanent` `source_hint` |

- 1번 시즌은 CBT다. 정식 시즌 1은 2번이고, 표시 이름으로 구분한다. 서버가 돌리는 시즌 번호는 `harbor-infra.yml`의 `season.id`다.
- 다른 콘텐츠의 `season:` 값도 이 번호를 쓴다. CBT(1)에는 BM이 없으므로('6. BM 기획' 7장) 시즌 1 패스 `shop/pass_s1.yml`·항구 축제 `shop/festivals.yml`·시즌 한정 코스메틱 `cosmetic/s1_rod_lighthouse`는 `season: 2`, 시즌 2 이후 기능인 진주 보물함 `shop/chests.yml`은 `season: 3`이다.
- **season 열이 있는 새 테이블은 반드시 `reset_plan.yml`의 `tables`에 넣는다.** 빠지면 PRECHECK와 리로드 검사에서 거부한다.

## 설정 값

`balance.yml` `season`:

| 키 | 내용 |
|---|---|
| `clock.check_s` · `clock.meta_reload_s` | 주차·초기화 확인 주기, `season_meta` 다시 읽기 |
| `ranking.check_s` · `ranking.lock_ttl_s` | 집계 서버 선출 주기, 잠금 유지 시간 |
| `hall_of_fame.announce` · `announce_throttle_s` | 명예의 전당 공지 |
| `gate.notice` | 새 시즌 첫 접속 안내 |

일일·주간 초기화 시각은 `harbor-infra.yml`의 `time`에 있다.

## 다른 모듈과의 연결

- **제공 서비스:** `SeasonClock`, `WeeklyLimitService`, `TitleService`, `RankingService`(원천 등록 `RankingSource`), `HallOfFameService`(제출), `SeasonHookRegistry`(전환 때 모듈 정리 훅 `SeasonHook`)
- **이벤트:** `DailyResetEvent`, `WeeklyResetEvent`, `SeasonWeekChangedEvent`, `PlayerSeasonEnteredEvent`, `TitleGrantedEvent`, `TitleEquippedEvent`, `HallOfFameUpdatedEvent`
- **쓰는 쪽:** Collection(칭호·기록 제출·랭킹), Boss(주간 제한·기록 제출), Quest(연대기 칭호), Shop(후원 칭호, 패스 정리 훅), Fishing(통발 정리 훅) 등

## 주의 사항

- Core가 시즌 번호를 `season_meta`에서 읽는 기능은 아직 없다. 전환 뒤 운영자가 모든 서버의 `harbor-infra.yml` `season.id`를 바꾼다.
- 프록시 점검 전환 API도 아직 없다.
- 시즌 수치는 모두 제안값이다.
