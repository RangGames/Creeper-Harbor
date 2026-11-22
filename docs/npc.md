# Harbor-Npc

NPC 엔티티, 대화 트리(조건·액션 레지스트리), 퀘스트 표시, 호감도·선물, 항구 평판, 떠돌이 NPC 일정, 컷씬 재생을 맡는다. 의존은 Core뿐이다. Quest·Economy·Jobs·Collection·Boss가 NPC api에 대화 액션·조건·표시를 등록하는 방향으로만 연결된다.

NPC 엔티티는 본 서버(`role: main`)에만 세운다. 인스턴스 서버에서는 컷씬·평판·호감도만 쓴다.

## 주요 기능

- **NPC:** `npcs.yml`의 위치에 세운다. 스킨은 리소스팩 텍스처 키, 플레이어 닉네임, 스킨 URL, textures 속성 값 중 하나로 정한다. 근처 플레이어를 바라본다. 서버 플래그(`spawn_flag`)가 서야 나타나는 NPC도 있다 (예: 수족관 2단계의 사육사).
- **대화:** 우클릭하면 대화가 시작된다. 조건에 맞는 첫 `entries` 노드에서 시작하고, 쪽(`pages`)을 넘기다 선택지(`options`)를 누르면 액션이 실행된다.
- **대화 방식 5가지:**

  | 방식 | 보이는 모습 | 선택지 |
  |---|---|---|
  | `dialog` (기본) | Dialog 화면. 화면을 막는다 | 화면 버튼 |
  | `hologram` | NPC 머리 위 말풍선. 그 플레이어에게만 보이고 타자 효과가 있다 | 채팅 버튼 |
  | `title` | 타이틀 = 화자, 서브타이틀 = 대사. 너무 길면 채팅에도 남긴다 | 채팅 버튼 |
  | `chat` | 채팅 대사 | 채팅 버튼 |
  | `actionbar` | 액션바 대사 (사라지지 않게 다시 보냄) | 채팅 버튼 |

  정하는 순서는 `dialogs/*.yml` 노드의 `style:` → `npcs.yml`의 `dialog_style:` → `balance.yml npc.dialog.default_style`이다. `dialog`가 아닌 방식은 화면을 막지 않는다. 같은 NPC를 다시 우클릭하면 다음 쪽으로 넘어가고, 노드의 `auto: <초>`로 자동으로 넘길 수도 있다. 멀어지면 대화가 끝난다. 퀘스트 수락 제안과 NPC 퀘스트 목록도 같은 방식을 따른다.
- **호감도:** NPC마다 따로 쌓인다.
  - 하루 첫 대화 +2.
  - 선물: NPC당 하루 1회. 좋아하는 선물 +15, 괜찮은 선물 +5, 표에 없는 아이템 +1, 싫어하는 선물은 감소. 어종은 품질만큼 더 오른다.
  - 단계: 낯섦 → 아는 사이 → 친구 → 단짝 → 신뢰. 친구 단계부터 그 NPC 상점 할인(3·5·8%)이 붙는다.
- **항구 평판:** 계정 전체 값이다. 등급은 이방인 · 이웃(300) · 단골(1000) · 항구의 일원(2000) · 항구의 기둥(3500).
  - 메인 스토리·지역 퀘스트·의뢰·보스 첫 처치·월드 보스·방류로 오른다. 출처별 기본값과 하루 상한이 있다.
  - 시즌 간 최고 등급(`best_rep_tier`)은 하우징 플롯 크기, 개인 어항 칸, 장식 구매 조건에 쓰인다.
- **떠돌이 NPC (`role: wanderer`):** 3시간 단위 시간대마다 후보 장소 중 하나로 옮긴다. 후보에 시간·날씨 조건을 달 수 있다 (예: 밤 + 폭풍에만 나타나는 선장). 도착을 공지할 수 있고, 대화 중이면 이동을 최대 60초 미룬다.
- **컷씬:** 카메라 경로, 배우(NPC·스킨), 타이틀, 대사, 소리, 시간·날씨를 시간축(틱)으로 재생한다. 본 컷씬은 건너뛸 수 있게 할 수 있다 (`skippable: seen`). 끝나면 원래 자리로 돌아온다.
- **퀘스트 표시:** Quest가 등록한 표시(받을 수 있음·보고 가능)를 NPC 머리 위에 띄운다.

## 플레이어 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/평판` | (없음) | 평판 값·등급·시즌 간 최고 등급·다음 등급 혜택·최근 획득 내역 |
| `/호감도` | (없음) | NPC별 호감도 |

## 관리자 명령

`/harbor npc …`는 `harbor.admin.npc`가 필요하다. 일부 하위 명령은 `harbor.admin.npc.<하위>`가 더 필요하다.

| 명령 | 추가 권한 | 설명 |
|---|---|---|
| `/harbor npc list` | | NPC 목록 |
| `/harbor npc tp <npc>` | | NPC에게 이동 |
| `/harbor npc respawn [npc]` | | 전체 또는 하나 다시 세우기 |
| `/harbor npc dialog <npc> <노드>` | `harbor.admin.npc.dialog` | 대화 노드 바로 열기 (시험용) |
| `/harbor npc affinity <플레이어> <npc> get\|set <값>\|add <값>` | `harbor.admin.npc.affinity` | 호감도 보기·설정·증감 |
| `/harbor npc rep <플레이어> get\|add <값>` | `harbor.admin.npc.rep` | 평판 보기·증감 |
| `/harbor npc wanderer <npc> [move <후보>\|reset]` | `harbor.admin.npc.wanderer` | 떠돌이 위치 보기·옮기기·일정대로 되돌리기 |
| `/harbor npc cutscene play <플레이어> <컷씬>` · `stop <플레이어>` · `restore <플레이어>` | `harbor.admin.npc.cutscene` | 컷씬 재생·중지·상태 복구 |

## 콘텐츠 파일

| 파일 | 내용 | 주요 필드 |
|---|---|---|
| `npcs.yml` | NPC | `id` `name` `role`(story·job·collect·shop·region·wanderer) `location` `skin` `pose` `dialog` `dialog_style` `gifts` `gift_lines` `affinity_levels` `spawn_flag` `hide_flag` `shop` `look_radius` `interact_radius` `schedule`(`slot_hours` `announce` `candidates`) |
| `dialogs/*.yml` | 대화 트리 | `id`(dialog/…) `entries`(`conditions` `node`) `nodes.<이름>`(`pages` `options`(`label` `actions` `goto` `conditions`) `style` `auto`) |
| `cutscenes/*.yml` | 컷씬 | `id` `world` `skippable` `time` `weather` `camera`(`t` `pos` `yaw` `pitch`) `actors` `titles` `lines` `sounds` `duration` `end` |

대사·버튼 문구는 `messages_ko.yml` 키로 쓴다. 키가 없으면 콘텐츠 검사에서 거부한다.

**대화 액션:**

| 제공 | 액션 |
|---|---|
| Npc 기본 | `goto` `page` `close` `affinity:add` `flag:set` `flag:unset` `gift:open` `cutscene:play` `sound` `message` `server:depart`(출항) `server:lobby`(로비로) |
| Quest | `quest:list` `quest:offer` `quest:accept` `quest:talk` `quest:turn_in` |
| Economy | `shop:open` `mail:open` `auction:open` `craft:open` |
| Jobs | `job:change_line` `job:reset_skill` |
| Collection | `aquarium:donate` `collection:hint` |
| Boss | `boss:enter` |

**대화 조건:** `affinity_min` `rep_tier_min` `flag` `not_flag` `server_flag` `not_server_flag` `job_tier` `level_min` `time` `weather` (기본), `quest_state` `quest_available` (Quest 등록)

## 설정 값

`balance.yml`:

| 절 | 내용 |
|---|---|
| `reputation.tiers` | 평판 등급 문턱·이름 |
| `reputation.sources` · `daily_caps` · `recent_size` | 출처별 기본 평판, 하루 상한, `/평판` 최근 내역 줄 수 |
| `npc.affinity` | 단계(`levels`: 문턱·이름·할인), `greet`, `gift_default`, `gift_quality_bonus`, `gifts_per_day`, `reactions` |
| `npc.dialog` | `timeout_seconds` `option_distance` `click_cooldown_ticks` `default_style` `watch_ticks`, `hologram`(높이·줄 너비·타자 속도·보이는 거리·배경), `title`(`stay_seconds` `max_chars`), `actionbar.refresh_ticks` |
| `npc.entity` | 시선 갱신 간격, Interaction 크기, 이름·표시 높이 |
| `npc.wanderer` | `slot_hours` `check_interval_ticks` `max_delay_seconds` |
| `npc.cutscene` | `restore_timeout_ms` `skip_window_ticks` `max_entities` `camera_step_ticks` |

## 다른 모듈과의 연결

- **제공 서비스:** `NpcService`, `DialogService`(액션·조건 등록, 대화 열기), `AffinityService`, `ReputationService`, `WandererService`, `CutsceneService`, `NpcMarkerRegistry`(머리 위 표시)
- **이벤트:** `NpcInteractEvent`, `DialogOptionSelectedEvent`, `DialogEndEvent`, `GiftGivenEvent`, `AffinityChangedEvent`, `AffinityLevelUpEvent`, `ReputationChangedEvent`, `ReputationTierChangedEvent`, `WandererMovedEvent`, `CutsceneStartEvent`, `CutsceneEndEvent`

## 주의 사항

- NPC 모습은 Mannequin 엔티티가 기본이다. 스킨·자세 지원 범위는 Phase 1 검증 항목이다. 지원하지 않는 자세(`pose`)는 오류 대신 경고 뒤 기본 자세로 둔다. Mannequin이 부족하면 Core 리그 인형으로 바꿔 끼울 수 있게 되어 있다.
- 컷씬 관전 카메라와 Interaction 히트박스 크기도 실제 서버에서 확인하지 않았다.
- NPC 좌표와 호감도·평판 수치는 맵 확정 전 제안값이다.
