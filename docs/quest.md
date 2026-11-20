# Harbor-Quest

YAML 퀘스트를 상태 기계(조건 → 목표 → 보상)로 돌리는 엔진이다. 어부 길드 게시판(일일·주간 의뢰), 수집 의뢰, 「항구 연대기」와 연대기 회상, 클라이맥스 개방 판정도 맡는다. NPC 엔티티·대화 화면·컷씬은 Npc가, 전직 실행은 Jobs가 맡는다.

퀘스트북 GUI, 게임 안 퀘스트 편집기, YAML 축약형, 알림 설정은 [harbor-quest/README.md](../harbor-quest/README.md)에 자세히 있다.

## 주요 기능

- **퀘스트 유형:**

  | 유형 | 설명 |
  |---|---|
  | `main` | 메인 스토리 (프롤로그~3장이 콘텐츠에 있음). 목표는 차례대로 진행한다. 시즌 이월되고 포기할 수 없다 |
  | `region` | 지역 퀘스트 |
  | `job` | 전직 퀘스트. 보고 단계에서 Jobs 전직 확인 화면이 뜬다 |
  | `daily` · `weekly` | 어부 길드 게시판 의뢰 |
  | `collect` | 수집 의뢰 (수집 의뢰 NPC의 게시판) |

- **수락과 보고:** 시작 NPC 대화에서 수락 제안이 뜨고, 목표를 마치면 보고 NPC에게 보고한다. 제안 방식은 퀘스트마다 `offer_style`로 정하고, 비우면 시작 NPC의 대화 방식을 따른다. 요구 조건은 레벨·전직 단계·주차·선행 퀘스트다.
- **목표 유형:** `catch` `deliver` `visit` `talk` `donate` `codex` `job_level` `job_tier` `boss` `chart_percent` `use_item` `flag` `cutscene` `affinity` `reputation`. `codex`·`job_level` 같은 게이지형은 세지 않고 지금 값을 반영한다. 다른 모듈이 목표 유형을 더 등록할 수 있다.
- **보상:** 골드·경험치·아이템·물비늘·평판·호감도·플래그·칭호·연대기 조각·전직·도감 힌트 (`rewards`의 `gold` `exp` `items` `mulbineul` `reputation` `affinity` `flags` `title` `chronicle` `job_advance` `hints`). 메인 장 마지막 보상은 진실의 조각·물비늘 3·평판·다음 지역 진입 플래그다.
- **추적:** 퀘스트 하나를 추적하면 보스바와 액션바 방향 표시(화살표·거리·높이)가 뜬다. 다른 액션바(지역·배·보스)가 나간 직후에는 덮지 않는다.
- **알림:** 수락·보고 가능·완료·포기·실패를 채팅·타이틀·액션바로 알린다. 메인 퀘스트는 따로 정할 수 있다.
- **어부 길드 게시판 (`/의뢰`):** 일일 3개 중 1개, 주간 3개 중 3개, 수집 2개 중 2개를 고른다. 골드는 해금한 가장 높은 지역 기준이다.
- **항구 연대기 (`/연대기`):** 메인 스토리 장 목록과 진행이다. 장의 `final` 퀘스트를 완료하면 장 클리어다.
  - **연대기 회상:** 지난 시즌에 이미 완료한 장의 지역에 새 시즌에 처음 들어가면 회상 보상을 받는다 (장당 물비늘 3, 시즌 최대 27).
  - 모든 장을 마치면 칭호 `title/chronicle_keeper`.
- **서버 진행도 · 클라이맥스:** 수족관 기부율 80% 이상이고, 7장 클리어 인원이 `max(50, 시즌 활동 유저 × 15%)` 이상이면 클라이맥스가 열린다. 5분마다 판정한다. 시작되면 서버 플래그 `climax_start`로 Boss가 월드 보스를 시작한다.
- **게임 안 퀘스트 편집기:** 상자 GUI로 퀘스트를 만들고 고친다. 저장하면 `content/quests/<파일>.yml`의 그 항목만 바꿔 쓰고 리로드한다. 검사에 걸리면 되돌린다.

## 플레이어 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/퀘스트` | `harbor.quest` | 퀘스트북 GUI (진행 중·받을 수 있음·완료) |
| `/퀘스트 목록` | `harbor.quest` | Dialog 목록 |
| `/퀘스트 표시` | `harbor.quest` | 추적 보스바 켜기/끄기 |
| `/퀘스트 추적 <퀘스트>` · `/퀘스트 포기 <퀘스트>` | `harbor.quest` | 추적, 포기 (포기 후 같은 주기 재수락 대기 10분) |
| `/의뢰` | `harbor.quest` | 어부 길드 게시판 |
| `/연대기` | `harbor.quest.chronicle` | 항구 연대기 |

## 관리자 명령

`/harbor quest …`는 `harbor.admin.quest`와 아래 하위 권한이 필요하다.

| 명령 | 권한 | 설명 |
|---|---|---|
| `/harbor quest editor [퀘스트]` | `harbor.admin.quest.editor` | 게임 안 퀘스트 편집기 |
| `/harbor quest gui <플레이어>` | `harbor.admin.quest.info` (버튼마다 `set`·`complete`) | 플레이어 퀘스트 상태·목표 진행을 클릭으로 고치기 |
| `/harbor quest info <플레이어>` | `harbor.admin.quest.info` | 퀘스트 상태 |
| `/harbor quest set <플레이어> <퀘스트> <상태>` | `harbor.admin.quest.set` | 상태 설정 |
| `/harbor quest progress <플레이어> <퀘스트> <key> <값>` | `harbor.admin.quest.set` | 목표 진행 설정 |
| `/harbor quest reset <플레이어> <퀘스트>` | `harbor.admin.quest.set` | 초기화 |
| `/harbor quest complete <플레이어> <퀘스트>` | `harbor.admin.quest.complete` | 완료 처리 |
| `/harbor quest board reroll <플레이어> <종류>` | `harbor.admin.quest.board` | 게시판 의뢰 다시 뽑기 |
| `/harbor quest server` · `server open` · `server start` | `harbor.admin.quest.server` | 서버 진행도 보기, 클라이맥스 강제 개방, 시작 요청 |

## 콘텐츠 파일

| 파일 | 내용 | 주요 필드 |
|---|---|---|
| `quests/main.yml` · `region.yml` · `job.yml` · `commissions.yml` | 퀘스트 | `id`(quest/…) `name` `type` `chapter` `description` `requires`(`quests` 등) `start_npc` `end_npc` `cutscene` `offer_style` `objectives`(`key` `type` `target` `count` `waypoint` `text`, 또는 축약형 `{catch: …}`) `rewards`(`gold` `exp` `items` `flags` …) |
| `board.yml` | 게시판 규칙 | `location` `collect_npc` `daily`·`weekly`·`collect`(`offers` `pick` `mulbineul` `reputation`) `hint_fallback_gold` `gold_by_region` |
| `chronicle.yml` | 연대기와 장 | `complete_title` `recap`(`mulbineul` `reputation` `max_mulbineul`) `chapters`(`chapter` `name` `final` `region` `area` `cutscene`) |
| `progress.yml` | 클라이맥스 조건 | `climax`(`donation_rate` `chapter` `min_clears` `clear_ratio`) `check_interval_minutes` `announce` |

- 목표 `key`는 저장된 진행을 이어 붙이는 기준이라 바꾸지 않는다.
- 선행 퀘스트 순환, 장 `final`이 메인 퀘스트인지, 품질 조건이 직업 단계 상한(`jobs.quality_cap`) 안인지를 검사한다.
- 지역 목표는 `point:<월드>,<x>,<y>,<z>,<반경>` 또는 `region:<ID>`로 쓴다.

## 설정 값

`balance.yml` `quest`:

| 키 | 내용 |
|---|---|
| `active_limit` | 진행 중 퀘스트 상한 (의뢰 제외, 20) |
| `abandon_cooldown_minutes` | 포기 후 재수락 대기 |
| `tracker` | `waypoint_actionbar` `waypoint_quiet_s` `waypoint_refresh_ticks` `waypoint_keep_s` `waypoint_arrive_m` `waypoint_vertical_m` `bossbar` |
| `journal.style` | `/퀘스트`를 `gui` 또는 `dialog`로 |
| `book.bar_width` · `book.icons` | 퀘스트북 막대 칸 수, 아이콘 재료 |
| `notify` | 사건별 알림 형태 (`chat` `title` `actionbar` `none`), `<사건>_main`은 메인 전용 |

## 다른 모듈과의 연결

- **제공 서비스:** `QuestService`, `ChronicleService`, `ServerProgressService`, `ObjectiveTypeRegistry`(목표 유형 등록), `ObjectiveSignal`(목표 신호 보내기), `RegionProgressProvider`
- **이벤트:** `QuestAcceptedEvent`, `QuestProgressEvent`, `QuestReadyEvent`, `QuestCompletedEvent`, `QuestAbandonedEvent`, `QuestStateChangedEvent`, `ChapterCompletedEvent`, `ChroniclePieceEvent`
- **쓰는 것:** Npc(대화 액션 `quest:*`, 조건 `quest_state`·`quest_available`, 머리 위 표시), Jobs(전직 확인), Fishing·Collection(포획·기부·도감 목표), Season(주차 조건)
- **선택 의존:** World(지역 진입·해도 목표), Boss(보스 목표). 있으면 이벤트를 이름으로 연결한다.
- **Shop:** 시즌 패스 경험치 출처로 퀘스트 완료를 등록한다.
- **원장 사유:** `QUEST_REWARD`

## 주의 사항

- 메인 4~7장 퀘스트(클라이맥스 조건 포함)는 아직 콘텐츠에 없다. 지금은 프롤로그~3장만 있다.
- 바닐라 위치 표시 막대(waypoint)는 Phase 1 검증 뒤 숨김 표식 엔티티로 바꿀 예정이다. 지금은 액션바로 방향을 보여 준다.
- 편집기로 저장한 파일은 DefaultContent가 "고친 파일"로 보고 기본값으로 덮어쓰지 않는다. 저장소 `content/quests/`에는 사람이 옮겨야 한다.
- 게시판·연대기·클라이맥스 수치는 모두 제안값이다.
