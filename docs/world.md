# Harbor-World

장소와 이동의 규칙을 맡는다. 지역 판정·진입 조건·밀어내기, 해도, 환경 디버프, 선박·탈것, 물고기 떼, 보물 지도, 해상 이벤트 스케줄러(폭풍·유령선·물고기 대이동·주간 낚시 대회), 하우징(플롯·권한·방문·좋아요·장식·개인 어항 자리)이 여기 있다.

## 주요 기능

- **지역과 해금:** 지역은 7개다. 크리퍼 항구 & 잔잔한 만 → 이끼 강 & 버섯 늪지 → 산호 군도 → 용암 호수 → 서리 협만 → 잿빛 해구 → 공허의 바다.
  - 해금 조건: 레벨, 퀘스트 플래그(메인 장 보상), 전직 단계, 아이템, 탈것(선박 탑승).
  - 조건이 안 되면 경계에서 밀어내고 조건 안내 Dialog를 띄운다. 막힌 지역에서 접속하면 안전 지점으로 옮긴다.
  - 지역 월드가 다른 서버에 있으면 그 서버로 옮긴다.
- **해도 (`/해도`):** 지역마다 탐사한 비율을 기록한다. 25·50·75·95%(완성) 단계마다 골드를 받고, 완성하면 물비늘 2개를 받는다. `/해도 지도 <지역>`은 방문한 지역의 해도 지도 아이템을 준다.
- **환경 디버프:**
  - 혹한: 실외·물속에서 쌓이고, 열원 근처에서 줄어든다. 60 이상이면 느려지고 100이면 피해.
  - 산소: 투구가 없으면 2배로 소모. 노틸러스를 타면 0.5배. 공기 주머니에서 회복.
  - 열기: 장화를 신으면 절반.
- **선박:**
  - `/선박`으로 가진 배를 근처 물에 소환하고, `/선박 회수`로 회수한다.
  - 배마다 속도 보정·화물 칸·내구도와 특성(쇄빙·폭풍 해역 진입·원양)이 있다.
  - 조선소(시설 8블록 안)에서 구매·업그레이드(속도·적재·선체 3갈래 × 3단계)·수리를 한다. 수리 키트도 쓸 수 있다.
  - 시즌 첫 접속 때 나룻배를 받는다.
  - 폭풍 해역에서는 내구도가 깎이고 번개를 맞는다. 내구도가 0이 되면 난파한다.
- **탈것:**
  - **항구 비행선 (해피 가스트):** 해금 지역 정류장끼리 노선이 있다. 그 지역을 배로 한 번 방문해야 노선이 열린다. 3분마다 출발하고, 한 마리에 4명이 탄다 (넘치면 한 마리 더). 비행 중에는 내릴 수 없다.
  - **노틸러스:** 잿빛 해구 잠수 정류장에서 무료로 빌린다. 내린 뒤 30초가 지나거나 해구를 벗어나면 회수된다.
- **물고기 떼:** 지역마다 `fishing_spots`개의 낚시터를 후보 자리에서 골라 10~20분마다 옮긴다. 물고기 떼 안에서 던지면 입질 대기 −20%, 희귀 가중치 +10%다. 숨은 낚시터는 탐험 2차 스킬로만 보인다.
- **보물 지도:** 낚시 중 드롭된다. 처음에는 어긋난 위치를 보여 주고, 64블록 안에 오면 정확한 위치를 보여 준다. 3블록 안에서 쓰면 3초 파는 연출 뒤 보상(골드·아이템, 지역 배율)을 받는다. 수령 때 도감 힌트를 줄 수도 있다.
- **해상 이벤트:** 한 서버가 1분마다 일정을 확인한다. 정해진 시간대에 확률로 발동하고, 5분 전에 예고한다.

  | 형식 | 내용 |
  |---|---|
  | `storm` 폭풍 | 해역 안 선박 내구도 피해·속도 감소·번개, 입질 대기 보정 |
  | `ghost_ship` 유령선 | 유령선 등장, 신화 어종 |
  | `migration` 물고기 대이동 | 대상 어종 가중치 ×3, 신화 어종 |
  | `tournament` 주간 낚시 대회 | 순위 보상 골드 |
  | `special` | 클라이맥스 다음 날 "첫 물고기의 귀환" 등 |

- **하우징 (`/부두`):** 하우징 월드(`harbor_homes`)에 개인 플롯을 받는다.
  - 하우징 월드는 시작할 때 없으면 만든다: `housing.server`로 정한 서버, 비우면 main 역할 서버. 칸마다 최대 플롯 크기의 풀밭 마당이 있고 사이 길은 얕은 물 위 가문비 판자다.
  - 기본 24칸이고, 평판 4등급(항구의 일원)부터 32칸.
  - 방문(`/부두 방문 <이름>`)과 좋아요가 있다. 공개 범위·입구 위치를 정할 수 있다.
  - 건축자를 추가·제거할 수 있다. 플롯 밖과 권한 없는 사람의 편집은 막는다.
  - 엔티티·레드스톤 수에 상한이 있다.
  - **장식 상점:** 골드로 블록·가구·수조를 산다. 산 장식은 인벤토리가 아니라 보관 수량으로 쌓이고, `/부두 장식` 팔레트에서 꺼내 설치한다. 일부는 평판 등급 조건이 있다.
  - **수조:** Collection 개인 어항 전시 자리가 된다.
- **해역 분위기 (데이터팩 없음):** `world/ambience.yml`대로 그 지역에 있는 플레이어에게만 배경음·환경음 루프, 리소스팩 post effect(화면 색조), 개인 날씨, 주변 파티클을 낸다. 시간대 `phases`(새벽 물안개, 밤 야광 플랑크톤 등)는 오버월드 시간으로 판정하고 앞뒤 `fade` 틱 동안 서서히 바뀐다 (네더·엔드 지역도 오버월드 시간). 배경음은 곡이 끝나면 다시 낸다 (자동 빌드 팩이 알려 준 곡 길이, sounds.yml `loop`가 더 길면 그 간격). 다시 낼 때 바닐라 배경음은 끊는다. 연출 강도 0(`/낚시 설정`)이면 post effect를 끄고 파티클을 줄인다.
  - 바이옴 안개 색·거리, 하늘 색, 조명 색은 데이터팩으로만 바뀌므로 post effect 색조로 대신한다.
- **웨이포인트 (로케이터 바):** 지금 지역의 물고기 떼(숨은 낚시터는 감지 스킬이 있을 때만)를 `waypoint.spot_range` 안에서, 보물 지도를 들고 있으면 지도가 가리키는 지점을 보여 준다 (Core `WaypointService`). 탐험 계열 `waypoint_range` 스탯만큼 거리가 늘어난다.

## 플레이어 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/해도 [지역]` | `harbor.world.chart` | 지역 해도 완성도 |
| `/해도 지도 <지역>` | `harbor.world.chart` | 해도 지도 아이템 받기 (방문한 지역만) |
| `/부두` | `harbor.world.housing` | 내 플롯으로 |
| `/부두 방문 <이름>` | `harbor.world.housing` | 다른 사람 플롯 방문 |
| `/부두 설정` · `좋아요` · `입구` | `harbor.world.housing` | 플롯 설정, 좋아요, 입구를 지금 위치로 |
| `/부두 장식` · `/부두 상점` | `harbor.world.housing` | 장식 팔레트, 장식 상점 |
| `/부두 건축자 추가\|제거 <이름>` | `harbor.world.housing` | 건축 권한 |
| `/선박` · `/선박 소환` · `회수` · `화물` · `정보` | `harbor.world.ship` | 선박 소환·회수·화물 칸·정보 |
| `/조선소` | `harbor.world.ship` | 조선소 (선박 구매·업그레이드·수리, 조선소 근처에서만) |
| `/비행선 [지역]` | `harbor.world.travel` | 항구 비행선 노선 |
| `/노틸러스` | `harbor.world.travel` | 심해 탈것 대여 |

`harbor.world.bypass`(op): 지역 진입 조건·하우징 보호·조선소 거리 제한을 무시한다.

## 관리자 명령

`/harbor world …`는 `harbor.admin.world`와 아래 하위 권한이 필요하다.

| 명령 | 권한 | 설명 |
|---|---|---|
| `/harbor world region info` · `region list` | `harbor.admin.world.region` | 지금 위치의 지역·세부 구역, 지역 목록 |
| `/harbor world chart set <플레이어> <지역> <0~100>` | `harbor.admin.world.chart` | 해도 비율 설정 |
| `/harbor world ship give <플레이어> <선박>` · `repair <플레이어>` · `recall <플레이어>` | `harbor.admin.world.ship` | 선박 지급·수리·회수 |
| `/harbor world event list` · `start <이벤트> [분]` · `stop <실행 ID>` | `harbor.admin.world.event` | 해상 이벤트 목록·시작·종료 |
| `/harbor world spot list` · `spot move <지역>` | `harbor.admin.world.spot` | 물고기 떼 위치, 즉시 옮기기 |
| `/harbor world plot info` · `tp <플레이어>` · `reset <플레이어>` · `transfer <원래 주인> <새 주인>` | `harbor.admin.world.plot` | 플롯 정보·이동·초기화·이전 |

## 콘텐츠 파일

| 파일 | 내용 | 주요 필드 |
|---|---|---|
| `world/regions.yml` | 지역 확장 (`regions.yml`과 같은 ID) | `order` `hourly_gold` `server` `spawn` `unlock`(`flags` `job_tier` `vehicle` `first_entry_only`) `areas` `docks`(airship·dive·pier) `stations`(shipyard·housing·decor_shop) `treasure_sites` `spot_candidates` `hidden_spots` `env` `air_pockets` `ice_area` |
| `world/ships.yml` | 선박 | `item` `base`(boat·chest_boat) `wood` `price` `speed` `cargo` `durability` `flags`(ice_break·storm·ocean) `requires` `upgrades`(`speed` `cargo` `hull`) `starter` `decor` `decor_donor` `engine_sound` `engine_pitch` |
| `world/events.yml` | 해상 이벤트 일정 | `type` `regions` `area` `window`(`days` `start` `end`) `chance` `min_gap_h` `duration_min` `warn_min` `requires_flag` `max_per_season` `modifiers` `fish_effects` `sounds` + 형식별 값(`storm` 등) |
| `world/treasure.yml` | 보물 지도 보상 | `region_mult` `tiers`(`weight` `gold` `items` `picks` `region`) `hint_chance` |
| `world/ambience.yml` | 해역 분위기 (데이터팩 없이) | `music` `ambient` `post_effect` `weather` `particles`(`type` `count` `radius` `height` `water` `color`) `phases`(`id` `time` `fade` `particles` `post_effect`) |
| `world/housing_decor.yml` | 장식 상점 | `kind`(block·furniture·tank) `material`·`block`·`furniture`·`model` `amount` `price` `reputation_min` `category` |

지역의 경계·진입 레벨·통발 자리는 Core `regions.yml`에 있다.

## 설정 값

`balance.yml` `world`:

| 키 | 내용 |
|---|---|
| `region` | `safe_distance` `push_speed` `dialog_cooldown_s` |
| `chart` | `step_count` `steps` `step_gold_ratio` `complete_mulbineul` |
| `treasure` | `fuzz` `exact_within` `claim_radius` `dig_seconds` |
| `ship` | `starter` `summon_water_radius` `summon_cooldown_s` `upgrade_cost` `repair_kit_ratio` `repair_kit_seconds` `ice_restore_s` `vanilla_accel` |
| `env` | `cold` `oxygen` `heat` |
| `spot` | `radius` `move_min_s` `move_max_s` `particle_radius` `radar_radius` `bite_wait` `rare_weight` `modifier_s` |
| `airship` | `interval_s` `capacity` `speed` `altitude` `board_radius` |
| `nautilus.return_after_s` | 노틸러스 회수 시간 |
| `housing` | `world` `server` `cell` `base_size` `large_size` `large_tier` `floor_y` `entity_limit` `redstone_limit` `anchor_limit` |
| `occupancy` | 서버 인원 발행 주기 |
| `event.check_s` | 이벤트 일정 확인 주기 |

`stats` 절의 `boat_speed` `boat_damage` `debuff_damage` `debuff_duration` `env_rate` `cold_debuff_time` `chart_reward_gold` `event_forewarn_s` `feature_storm_sailing` `feature_hidden_spot` `feature_school_radar`가 World 수치를 보정한다.

## 다른 모듈과의 연결

- **제공 서비스:** `RegionService`, `ChartService`, `ShipService`, `HousingService`, `FishingSpotService`, `TreasureService`, `SeaEventService`, `RideService`
- **제어 API:** `ShipService.summon`(증서 사용과 같음, 대기 시간 무시 선택) · `recall` · `setDurability`(관리자, 1~최대), `HousingService.visit`(방문 정책 검사, 무시 선택) · `setMember` · `setVisitPolicy`(`/부두` 명령과 같은 DB 기록 + 서버 간 부지 변경 알림), `RideService.board`(`/비행선 <지역>`) · `launch`(관리자, 바로 출발) · `riding` · `queued` · `destination` · `stop`. 지역 해금은 따로 API가 없고 입장 조건의 플래그로 한다. Skript 문법은 harbor-skript 문서의 월드 절.
- **확장 지점:** `DisplayAnchor`(Collection 개인 어항 자리), `ShipSkinProvider`(Shop 선박 스킨)
- **이벤트:** `RegionEnterEvent`, `RegionLeaveEvent`, `RegionFirstVisitEvent`, `RegionBlockedEvent`, `ChartProgressEvent`, `ShipSummonEvent`, `ShipWreckedEvent`, `FishingSpotMovedEvent`, `TreasureClaimedEvent`, `SeaEventWarnEvent`, `SeaEventStartEvent`, `SeaEventEndEvent`, `PlotLoadedEvent`, `PlotUnloadedEvent`, `PlotLikedEvent`, `DisplayAnchorChangedEvent`
- **Fishing 확장 지점 구현:** `RegionKeyResolver`(세부 구역 판정), `TreasureDropHook`(보물 지도 드롭). 해상 이벤트의 낚시 효과는 `harbor:event` 메시지로 Fishing에 보낸다.
- **선택 의존:** Economy(조선소 결제 `ShopService.charge`, 수리 `RepairService`), Npc(평판 등급·대화), Season(시즌 훅)
- World는 Quest·Collection·Shop에 의존하지 않는다. 그쪽이 World api에 등록한다.
- **원장 사유:** `TREASURE`, `HOUSING_DECOR`, `SHIP_PURCHASE`, `SHIP_UPGRADE`, `CHART_REWARD`, `TOURNAMENT_REWARD`, 선박 수리는 `REPAIR`

## 주의 사항

- **Phase 1 검증 항목:**
  - 보트 속도 보정: 조종자가 앞으로 누르는 동안 틱마다 가속을 더하는 방식. 기준값은 `vanilla_accel`.
  - 해피 가스트 비행선 조종 방식
  - 보물 지도 위치 표시: Paper API가 없어 위치 표시 막대 대신 액션바로 보여 준다.
- 탈것 엔티티는 저장하지 않는다. 서버가 꺼지면 비행선 탑승자를 도착지로 옮긴다.
- 해도 이미지, 구조물(.nbt)은 아직 준비되지 않았다.
- 좌표·이벤트 확률·내구도 등 문서에 없는 값은 모두 제안값이다.
