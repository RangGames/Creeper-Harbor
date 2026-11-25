# Harbor-Collection

도감·힌트·최대어 기록·항구 수족관(수조 연출)·개인 어항 전시·방류·랭킹 전광판을 맡는다. 포획 판정은 하지 않는다. Fishing의 포획 이벤트를 받아 기록만 한다.

화면 구성(도감 탭·필터·정렬, 보상 트랙, 기록 GUI, 수족관 진행 화면)은 [harbor-collection/README.md](../harbor-collection/README.md)에 자세히 있다.

## 주요 기능

- **도감:** 처음 낚은 어종을 등록한다. 누적 도감과 시즌 도감이 따로 있다.
  - 낚싯대 포획은 도감과 최대어 기록에 모두 들어간다. **통발 수확**(Fishing `TrapHarvestEvent`)은 도감에만 들어가고 기록에서는 빠진다.
  - **release_only 보스 어종**(해구의 군주·녹빛 폭뢰어)은 아이템이 없다. 낚으면(방류 포획) 도감에 등록되고, Boss 보스전(`BossEncounterEndEvent`)이 CLEARED로 끝나면 이 서버에 있는 참가자 모두의 도감에 등록된다. 크기는 어종 범위 안에서 무작위, 품질 ★1(제안값)이고 기록에는 들어가지 않는다. 스토리 전투(`story: true`, 6장 진정 전투)는 등록하지 않는다.
  - 시즌 도감 첫 등록은 **토스트**(발전과제 알림)로 알린다. 데이터팩 없이 서버 시작 때 `harbor:codex/root`·`harbor:codex/<지역>/<어종>` 발전과제를 메모리에만 등록하고(`UnsafeValues.loadAdvancement(…, false)`, 파일을 쓰지 않음), 등록 순간 기준을 줬다가 `collection.toast.keep_ticks` 뒤 다시 회수한다. 그래서 발전과제 화면에는 남지 않고 다음 시즌에도 다시 뜬다. 아이콘은 어종 모델(리소스팩에 있을 때), 테두리는 등급(전설 이상 challenge, 영웅 goal). 등록에 실패하거나 끄면 예전처럼 제목 알림을 쓴다. 리로드로 추가된 어종은 재시작 전까지 제목 알림이다.
  - 희귀 이상을 처음 낚으면 물비늘 1개를 받는다.
  - **지역 완성**(시즌 도감, 신화 제외): 칭호와 물비늘 3개. 칭호는 7개 지역 모두 `titles.yml`의 `title/codex_region/<지역>`에 있다 (항구·늪지·산호·용암·협만·해구·공허 박물학자, 이름은 제안값).
  - **누적 달성률 25·50·75·100%:** 칭호. 치장품은 Shop이 같은 이벤트로 준다.
  - **시즌 도감 달성률 20·40·60·80%:** 버프 (경험치·판매가·희귀 가중치 소폭).
- **힌트:** 미등록 어종은 실루엣과 힌트로 보인다. 힌트 단계(0~3)는 NPC 대화(`collection:hint`), 퀘스트 보상(`rewards.hints`), 보물 지도 수령으로 오른다.
  - 단계별 공개: 0 지역 → 1 시간대 → 2 날씨·미끼(아이템 이름으로) → 3 세부 구역·달·필요 직업 단계·`hint_text`.
  - `hint_text`(추가 제안): `fish/*.yml` 어종의 선택 필드. 힌트 3단계에 "메모"로 보인다.
  - 보물 지도: World `TreasureClaimedEvent`의 `hint()`가 참이면 그 지도의 지역을 먼저 보고 무작위 힌트 하나를 준다 (`HintService.grantRandom`, 출처 `treasure`). 줄 힌트가 없으면 안내만 한다.
- **최대어 기록:** 어종마다 시즌·누적 최고 크기를 남긴다. 리그는 기록 때의 직업 단계로 나뉜다 (1차 · 2차 · 전체). 서버 TOP 10과 "TOP 10까지 몇 cm"를 보여 준다. 서버 기록을 깨면 이벤트가 나고, 명예의 전당(Season)에 제출된다.
- **항구 수족관:** 모든 플레이어가 어종을 기부해 서버 단계를 올린다.
  - 단계는 기부된 어종 비율로 정한다: 10% · 25% · 45% · 65% · 80%.
  - 단계마다 서버 플래그가 서고 서버 전체 버프가 붙는다. 구조물(.nbt)도 바뀐다. 2단계에는 사육사 NPC가 나타나고, 3단계에는 기념품 상점이 열린다.
  - 개인 점수는 기여 순위에만 쓴다. 점수는 등급 점수 × 품질 보너스이고, 서버 첫 기부 어종은 ×3이다. 같은 어종은 한 사람당 시즌 5마리까지만 인정한다.
  - 수조 안에서 기부된 어종의 리그가 헤엄친다. 수족관 건물 안에 있는 사람에게만 보이고, 동시 표시 수 상한을 넘으면 교대로 보인다.
- **방류:** 손에 든 물고기를 도감에 남기고 바다로 돌려보낸다. 골드는 없다.
  - 희귀 이상은 평판 +3(하루 상한 30)과 30분 크기 행운 버프를 준다.
  - 서버 플래그 `climax_done`(월드 보스 클리어)이 선 뒤에 열린다.
- **개인 어항 전시:** 하우징 플롯의 수조 자리(World `kind: tank` 장식)에 물고기를 전시한다.
  - 기본 6칸이고, 시즌 간 최고 평판 등급 3·5에서 +2·+4칸.
  - Shop 어항 테마 코스메틱으로 바닥 블록·파티클이 바뀐다.
  - 장식에 `tank_model`(World housing_decor.yml)이 있으면 수조 블록 위에 그 모델(틀·물·장식)을 씌운다. 수조 상자 크기만큼 늘리고, 놓은 쪽이 앞이 되게 돌린다. 물고기와 같이 저장되지 않는 표시 엔티티라 플롯을 볼 때마다 다시 그린다.
  - 큰 수조(World `size`·`fish`)는 물고기 여러 마리가 수조 전체를 헤엄친다. 마리마다 전시 칸 하나를 쓰고, 수조가 넓을수록 물고기가 크게 보인다. 우클릭하면 어느 물고기를 바꿀지 고르는 창이 열리고, 바라보면 수조 안 물고기 목록이 뜬다.
  - 수조를 바라보면 물고기 정보가 바라보는 사람에게만 뜬다 (`display_case.yml` `hover_distance`, 0이면 끔).
- **랭킹 전광판:** 어시장 광장에 Season 랭킹 캐시를 페이지로 돌려 보여 준다.

## 플레이어 명령

| 명령 | 권한 (`paper-plugin.yml`) | 설명 |
|---|---|---|
| `/도감 [플레이어]` | `harbor.collection.view` | 도감 GUI |
| `/도감 보상` · `/도감 최근` | `harbor.collection.view` | 보상 트랙, 최근 등록순 |
| `/기록 [어종] [채팅]` | `harbor.collection.record` | 최대어 기록 GUI (채팅을 붙이면 채팅으로) |
| `/수족관` · `/수족관 상태` | `harbor.collection.aquarium` | 수족관 진행 화면, 상태 한 줄 |
| `/수족관 기부` · `/수족관 기부함` | `harbor.collection.aquarium` | 손에 든 한 마리 기부, 기부함 |
| `/방류` | `harbor.collection.release` | 손에 든 어종 방류 |

## 관리자 명령

`/harbor collection …`은 `harbor.admin.collection`이 필요하다.

| 명령 | 설명 |
|---|---|
| `/harbor collection display <플롯> clear <칸>` | 개인 어항 전시 칸 비우기 |
| `/harbor collection entry <플레이어> set <어종> [크기 품질]` | 도감 등록 |
| `/harbor collection entry <플레이어> remove <어종>` | 도감 삭제 |
| `/harbor collection record remove <어종> <리그> <플레이어>` | 최대어 기록 삭제 |
| `/harbor collection hint <플레이어> <어종> <0~3>` | 힌트 단계 설정 |
| `/harbor collection aquarium recount` | 수족관 기부율 다시 세기 |
| `/harbor collection aquarium stage <단계>` | 수족관 단계 강제 설정 |

## 콘텐츠 파일

| 파일 | 내용 | 주요 필드 |
|---|---|---|
| `collection.yml` | 도감 보상 | `first_catch`(`min_grade` `mulbineul`) `region_complete`(`mulbineul` `title_prefix`) `total_milestones`(`percent` `title`) `season_buffs`(`percent` `modifiers`) |
| `aquarium.yml` | 수족관 | `points` `quality_bonus` `first_species_mult` `per_player_species_cap` `stages`(`stage` `rate` `flag` `modifiers` `structure`) `display`(수조 좌표·`tanks`·`visible_max`·`rotate_minutes` 등) |
| `release.yml` | 방류 | `flag` `reward_min_grade` `reputation` `buff`(`stat` `value` `minutes`) |
| `display_case.yml` | 개인 어항 | `base_slots` `rep_bonus` `base_theme_slots` `part_budget` `view_radius` `info_seconds` `hover_distance` `swim_radius` `simple_rig` `default_theme` `themes`(`cosmetic` `floor` `particle`) |
| `billboard.yml` | 랭킹 전광판 | `server` `world` `at` `yaw` `line_width` `page_seconds` `lines` `pages`(`board` `league` 또는 `region`) |

## 설정 값

- `balance.yml` `collection.menu.bar_width`: 도감·보상 트랙·수족관 막대 칸 수 (제안값 10)
- `balance.yml` `collection.toast.enabled`: 도감 등록 토스트 (false면 제목 알림. 등록은 시작 때라 켜기는 재시작 뒤)
- `balance.yml` `collection.toast.keep_ticks`: 토스트 뒤 발전과제를 회수하기까지 틱 (제안값 20)
- 문구: `messages_ko.yml`의 `collection.*`

## 다른 모듈과의 연결

- **제공 서비스:** `CollectionService`, `HintService`, `RecordService`, `AquariumService`, `ReleaseService`, `DisplayCaseService`
  - `CollectionService.registerEntry`·`removeEntry`: `/harbor collection entry set|remove`와 같은 관리용 쓰기 (접속 중인 플레이어만)
  - `RecordService.weeklyRecords`: 이번 주 서버 기록 (최신순, 이 서버가 받은 것만, 주간 초기화 때 비움)
- **이벤트:** `CollectionRegisteredEvent`, `CollectionMilestoneEvent`, `RecordBrokenEvent`, `AquariumDonatedEvent`, `AquariumStageChangedEvent`, `FishReleasedEvent`
- **쓰는 것:**
  - Fishing: 포획·통발 수확 이벤트를 받고, `CollectionProvider`로 첫 포획 여부를 알려 준다.
  - Season (필수): 칭호 지급, 랭킹 원천, 명예의 전당 제출, 시즌 훅.
  - Npc (선택): 대화 액션 `aquarium:donate`·`collection:hint`, 최고 평판 등급(어항 칸), 방류 평판.
  - World (선택): 개인 어항 수조 자리(`DisplayAnchor`), 편집 권한, 플롯 로드·언로드, 보물 지도 힌트(`TreasureClaimedEvent`).
  - Boss (선택): `BossEncounterEndEvent`로 release_only 보스 어종 도감 등록. Boss가 Shop을 거쳐 Collection 위에 있으므로 컴파일 의존 없이 서버 시작이 끝난 뒤(`ServerLoadEvent`) 클래스 이름으로 구독한다 (`CatchSources`).
  - Shop (선택): 어항 테마 보유 확인. Shop은 Collection보다 뒤에 켜지므로 서비스로 찾는다.
- **원장 사유:** `CODEX_FIRST_CATCH`, `CODEX_REWARD`

## 주의 사항

- `paper-plugin.yml`에 플레이어 권한(`harbor.collection.*`)과 `harbor.admin.collection.display`가 정의되어 있다. 하지만 지금 명령 코드는 이 권한을 따로 확인하지 않는다. 플레이어 명령은 누구나 쓸 수 있고, 관리 명령은 `harbor.admin.collection`만 본다.
- 수조 좌표·단계 기부율·점수는 시제품 맵 기준 제안값이다.
- 수조 구조물(.nbt)은 아직 준비되지 않았다.
