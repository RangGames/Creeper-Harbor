# Harbor-Web 이벤트 명세 (Redis · 웹훅)

게임 이벤트를 밖으로 받는 방법은 세 가지다.

| 방법 | 받는 쪽 | 보장 |
|---|---|---|
| Redis `harbor:events` 직접 SUBSCRIBE | 내부망 서비스 (봇, 백엔드) | 실시간, 놓치면 끝 (pub/sub) |
| 웹훅 (`json` / `discord`) | HTTPS 엔드포인트, 디스코드 | 실패 시 재시도, 그 뒤 버림 |
| REST `GET /v1/events` (폴링) | 웹 백엔드 | REST 서버 메모리의 최근 N개만 ([api.md](api.md#get-v1events)) |

이 문서는 코드를 기준으로 썼다. 코드 위치는 api.md의 [Source map](api.md#source-map)에 있다. 코드와 다르면 코드가 맞다.

## 1. 흐름

```
[모든 게임 서버]
  모듈 Bukkit 이벤트 ──(Harbor-Web EventRelay, MONITOR, 취소된 이벤트 제외)──▶ EventSerializer ──▶ Core ExternalEvents.emit
  접속/퇴장, Harbor-Web 시작/종료 ─────────────────────────────────────────────────────────────▶ ExternalEvents.emit
  다른 플러그인 (Skript 등) ── Harbor.api().events().emit(...) ─────────────────────────────────▶ ExternalEvents.emit
                                                                                                     │
                                                                              Redis PUBLISH harbor:events
                                                                                                     │
              ┌──────────────────────────────────────┬───────────────────────────────────┬──────────┘
              ▼                                      ▼                                   ▼
  [웹훅 서버] WebhookDispatcher              [REST 서버] RecentEvents             [외부] 직접 SUBSCRIBE
  (harbor:lock:webhook 잡은 한 대만 발송)      GET /v1/events
```

- **발행:** 각 서버의 `config.yml events.publish`(기본 `true`)가 켜져 있어야 그 서버의 모듈 이벤트가 나간다. `events.exclude`의 glob에 맞는 type은 구독 자체를 하지 않는다.
- **Redis 발행이 실패하면:** 로그만 남기고 게임은 계속된다. 그 이벤트는 사라진다 (다시 보내지 않는다). Core 서비스가 없을 때 `api.events()`는 아무것도 하지 않는 `ExternalEvents.NOOP`이다.
- **type 형식:** `^[a-z0-9_]+(\.[a-z0-9_]+)+$` (`모듈.사건`). 틀리면 발행하지 않고 경고만 남긴다.

## 2. Redis 메시지 형식

채널은 `harbor:events` 하나다. 메시지는 Harbor 공통 봉투(Common `redis/Envelope`) JSON 글이다.

> **필드 이름 주의:** Redis 원본 메시지는 Common `ExternalEvent` 레코드를 그대로 쓰므로 **camelCase**(`occurredAt`, `playerName`, 봉투의 `sentAt`)다. REST `/v1/events`와 웹훅 `json` 본문은 같은 이벤트를 **snake_case**(`occurred_at`, `player_name`)로 옮겨 보낸다 ([REST·웹훅 형식](#rest웹훅-형식-snake_case)). data 안의 키는 어디서나 같다 (원래 snake_case).

```json
{
  "type": "ExternalEvent",
  "server": "main-1",
  "sentAt": "2026-11-20T12:00:05.912Z",
  "payload": {
    "id": "a8d2b7c4-0f7e-4b39-8a51-1b6f2d3e4c55",
    "type": "collection.record_broken",
    "version": 1,
    "server": "main-1",
    "occurredAt": "2026-11-20T12:00:05.901Z",
    "player": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
    "playerName": "Steve",
    "data": {
      "fish": "harbor:fish/harbor_mackerel",
      "fish_name": "항구 고등어",
      "league": "all",
      "player": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "previous": 39.8,
      "scope": "server",
      "size": 41.2
    }
  }
}
```

### 봉투

| 필드 | 형식 | 설명 |
|---|---|---|
| `type` | string | 항상 `"ExternalEvent"`. 다른 값이면 무시한다 (채널에 다른 레코드가 실릴 여지를 둔 설계) |
| `server` | string | 발행한 서버 ID (`harbor-infra.yml`) |
| `sentAt` | string | 발행 시각, ISO-8601 UTC |
| `payload` | object | 아래 ExternalEvent |

### ExternalEvent (payload)

| 필드 | 형식 | 항상 있음 | 설명 |
|---|---|---|---|
| `id` | string (UUID) | 예 | 이벤트마다 새로 만든 무작위 UUID. 중복 제거 키 |
| `type` | string | 예 | 아래 카탈로그의 type |
| `version` | integer | 예 | data 형식 버전. 지금은 모두 `1`. 필드를 빼거나 뜻을 바꿀 때만 올리고, 필드 추가는 그대로 둔다 |
| `server` | string | 예 | 이벤트가 난 서버 ID |
| `occurredAt` | string | 예 | 이벤트를 만든 시각 (그 서버 시계), ISO-8601 UTC. 소수 초 자릿수는 일정하지 않다 |
| `player` | string (UUID) | 아니오 | 관련 플레이어. 없으면 **키가 없다** (null을 쓰지 않음) |
| `playerName` | string | 아니오 | 플레이어 이름. `player`가 있으면 대부분 있다 (아래 규칙) |
| `data` | object | 예 | type별 내용. 값은 문자열·숫자·불리언·배열·객체만. null 값은 빠진다 |

**player / playerName이 정해지는 규칙** (`EventSerializer`, `EventRelay`):

- 원본 이벤트의 getter를 이름순으로 훑으며, 처음 만나는 `Player` 객체 또는 이름이 `player`/`uuid`인 UUID 값이 `player`가 된다.
- `playerName`: 그 값이 `Player` 객체면 그 이름. UUID만 있으면 발행 서버가 찾는다: 그 서버에 접속 중인 플레이어 → 최근에 본 이름(접속·조회 때 기억) → players 테이블(가상 스레드에서 조회한 뒤 발행). players 행도 없으면 `playerName` 없이 나간다.
- 구매자/판매자처럼 `player`라는 getter가 없는 이벤트는 `player`도 없다 (data 안의 `buyer`·`seller`를 본다. 이 UUID들의 이름은 채우지 않는다).

### data 변환 규칙

모듈 이벤트의 data는 원본 Bukkit 이벤트 클래스의 public 무인자 getter를 읽어 만든다 (`EventSerializer`). 직접 `emit`한 이벤트(server.*, 외부 플러그인)는 넘긴 Map 그대로다.

| 원본 값 | JSON |
|---|---|
| getter 이름 | snake_case (`sizeCm()`/`getSizeCm()` → `size_cm`, `firstCatch()` → `first_catch`) |
| `Player`, `OfflinePlayer`, `UUID` | UUID 문자열 |
| Harbor ID (`HarborId`, `FishId`·`QuestId`·`RegionId` 같은 래퍼) | `"harbor:<종류>/<이름>"` 문자열. 콘텐츠에서 이름을 찾으면 `<필드>_name`을 옆에 더한다 (예 `fish` + `fish_name`) |
| enum | 소문자 이름 (`SERVER` → `"server"`) |
| 직업 단계 `JobTier` | 숫자 단계 1~3, 옆에 `<필드>_key`로 `"first"`·`"second"`·`"third"` (예 `tier: 2`, `tier_key: "second"`) |
| `ContentSnapshot` | 내보내지 않는다. 대신 `entry_counts`: `reloaded_types`에 있는 형식별 항목 수 |
| 문자열 Set | 정렬한 배열 (Set은 순서가 없어서) |
| 레코드 | 객체 (구성 요소 이름을 snake_case로) |
| 이름이 `…Def`로 끝나는 콘텐츠 정의 레코드 (`FishDef` 등) | `{id, no, name, grade}`만 |
| `Location` | `{world, x, y, z}` (좌표는 소수 첫째 자리) |
| `ItemStack` | `{material, amount}` (`material`은 `minecraft:cod` 같은 키) |
| `Entity` | `{type, uuid}` |
| `Instant`·`LocalDate` 등 시간 | ISO-8601 문자열 (`Instant` → `…Z`, `LocalDate` → `2026-11-20`) |
| `Duration` | ISO-8601 기간 (`PT30S`) |
| 텍스트 컴포넌트 | 평문 |
| `Optional` | 안의 값 (비면 키 없음) |
| 리스트·배열·Map | 배열·객체 (Map 키는 위 규칙으로 문자열) |
| 그 밖 / 깊이 5 초과 | `toString()` 글 |

- **`_name` 필드:** ID의 종류(경로 첫 마디, 예 `fish`)와 같은 이름의 콘텐츠 형식이 있고 그 항목에 `name`이 있을 때만 붙는다. 리스트 안의 ID 문자열(예 `participants`)에는 붙지 않지만, 리스트 안의 레코드 객체에는 붙는다.
- **품질(`Quality`)은 레코드라 `{"stars": 4}` 객체**로 나온다. 시즌(`SeasonId`)도 `{"value": 1}` 객체다.
- **키 순서는 정해져 있지 않다** (`Map.copyOf`). 순서에 기대지 않는다.

### 숫자 표기

| 경로 | 정수 값 예 (`online` = 13) | 실수 값 예 |
|---|---|---|
| Redis 원본 메시지 | `13` | `41.2` |
| REST `GET /v1/events` | `13` | `41.2` |
| 웹훅 `json` 본문 | `13` | `41.2` |

REST 서버와 웹훅 서버는 Redis 메시지의 `data`를 원본 JSON으로 받은 뒤 정수는 정수(long), 소수점이나 지수가 있는 값은 실수(double)로 읽는다 (Gson `ToNumberPolicy.LONG_OR_DOUBLE`, `EventWire`). 그래서 정수는 어디서나 정수로 나오고, 원본이 `33.0`처럼 실수로 쓴 값은 실수로 남는다. long 범위의 정수도 정밀도를 잃지 않는다 (받는 쪽 JSON 파서가 2^53을 넘는 수를 다루는지는 따로 확인한다).

### REST·웹훅 형식 (snake_case)

REST `/v1/events`의 `items[].event`와 웹훅 `json` 본문은 같은 객체다. 필드 순서는 아래 표 순서다 (data 안의 키 순서는 정해져 있지 않다).

| 필드 | Redis 원본 필드 | 항상 있음 |
|---|---|---|
| `id` | `id` | 예 |
| `type` | `type` | 예 |
| `version` | `version` | 예 |
| `server` | `server` | 예 |
| `occurred_at` | `occurredAt` | 예 |
| `player` | `player` | 아니오 |
| `player_name` | `playerName` | 아니오 |
| `data` | `data` | 예 |

## 3. 이벤트 카탈로그

출처: `relay/EventCatalog.ENTRIES`(모듈 이벤트 50종) + `EventCatalog.SERVER_TYPES`·`HarborWebPlugin`(서버 이벤트 5종) = **55종**. 이 밖의 type은 다른 플러그인이 `emit`으로 더한 것이다 (예 `skript.*`).

- 모듈 이벤트는 **그 모듈 플러그인이 켜진 서버에서만** 구독된다. 이벤트는 그 사건이 처리된 서버에서 한 번 난다. 단, 표에서 "서버마다"라고 쓴 이벤트는 같은 사건이 **서버 수만큼 따로** 발행된다 (id가 서로 다르다).
- "player" 열: ✔ = `player` 있음 (`playerName`은 발행 서버가 찾아 넣으므로 players 행이 있으면 있다), ✔이름 = 원본 이벤트가 `Player`를 줘서 `playerName`이 항상 있음, – = 없음.
- data 필드의 `?`는 값이 없으면 키가 없다는 뜻이다. `X_name?`은 앞 절의 `_name` 규칙.
- 공통 객체 형식은 카탈로그 뒤의 [공통 객체](#공통-객체)에 있다.

### server (Harbor-Web, Core)

| type | 출처 | player | data | 비고 |
|---|---|---|---|---|
| `server.started` | Harbor-Web 시작 | – | `role`: `"main"`·`"lobby"`·`"instance"` | `events.publish`가 켜진 서버마다 |
| `server.stopping` | Harbor-Web 종료 | – | (빈 객체) | 종료 중 비동기 발행이라 못 나갈 수 있다 |
| `server.player_join` | `PlayerJoinEvent` | ✔이름 | `online`: 그 서버 접속 인원(들어온 사람 포함) | 서버 이동 때도 도착 서버에서 난다 |
| `server.player_quit` | `PlayerQuitEvent` | ✔이름 | `online`: 나간 뒤 그 서버 인원 | 서버 이동 때도 출발 서버에서 난다 |
| `server.content_reloaded` | Core `ContentReloadedEvent` | – | `reloaded_types`: string[] (이번에 다시 읽은 콘텐츠 형식, 정렬), `entry_counts`: `{<형식>: int}` (그 형식들의 리로드 후 항목 수, 파일 하나짜리 형식은 1) | 리로드한 서버마다 |
| `server.webhook_test` | `/harbor web test <대상>` | 이름만 | `target`: 대상 이름 | **Redis로 발행하지 않는다.** 그 웹훅 대상에만 바로 보낸다. `player_name`은 명령을 친 사람(콘솔이면 `CONSOLE`), `player` 없음 |

### economy.currency_changed (Core)

| type | 원본 이벤트 | player | data |
|---|---|---|---|
| `economy.currency_changed` | Core `CurrencyChangedEvent` | ✔ | `player`, `currency`: `"gold"`·`"mulbineul"`, `delta`: int(±), `balance`: int(처리 후 잔액), `reason`: LedgerReason 소문자 (예 `"fish_sell"`, `"quest_reward"`) |

변경을 처리한 서버에서 난다. 자주 나는 이벤트라 필요 없으면 `events.exclude`에 넣는다. `reason`은 REST 원장의 `reason`과 같은 소문자 키다.

### quest (Harbor-Quest)

| type | 원본 이벤트 | player | data |
|---|---|---|---|
| `quest.accepted` | `QuestAcceptedEvent` | ✔이름 | `player`, `quest`, `quest_name?`, `type`: 퀘스트 종류 문자열 |
| `quest.completed` | `QuestCompletedEvent` | ✔ | `player`, `quest`, `quest_name?`, `type`, `cycle`: 반복 주기 키 (`"once"`, `"d2027-05-03"`, `"w1-3"`), `rewards`: [QuestRewardSummary](#questrewardsummary) |
| `quest.abandoned` | `QuestAbandonedEvent` | ✔이름 | `player`, `quest`, `quest_name?` |
| `quest.chapter_completed` | `ChapterCompletedEvent` | ✔ | `player`, `chapter`: int, `recap`: bool (연대기 회상이면 true) |
| `quest.chronicle_piece` | `ChroniclePieceEvent` | ✔ | `player`, `piece`: string, `owned`: int(얻은 뒤 모은 수), `total`: int |

### economy (Harbor-Economy)

| type | 원본 이벤트 | player | data |
|---|---|---|---|
| `economy.auction_listed` | `AuctionListedEvent` | – | `seller`, `listing_id`, `item_id`: string, `amount`, `price`, `fee` |
| `economy.auction_sold` | `AuctionSoldEvent` | – | `buyer`, `seller`, `listing_id`, `item_id`, `price`, `fee`, `held`: bool (판매 대금 보류) |
| `economy.fish_sold` | `FishSoldEvent` | ✔ | `player`, `lines`: `[{fish, fish_name?, quality: {stars}, size_cm, price}]`, `total` |
| `economy.item_crafted` | `ItemCraftedEvent` | ✔ | `player`, `recipe`, `recipe_name?`, `result`, `result_name?`, `amount`, `gold` |
| `economy.shop_purchase` | `ShopPurchaseEvent` | ✔ | `player`, `shop`, `shop_name?`, `item?`, `item_name?`, `key`: 상점 항목 키 (바닐라 재료면 `vanilla/<키>`이고 `item` 없음), `amount`, `price` |
| `economy.trade_flagged` | `TradeFlaggedEvent` | – | `buyer`, `seller`, `ref_id`, `score`: number, `reasons`: string[], `held`: bool |
| `economy.market_updated` | `MarketUpdatedEvent` | – | `hour`: ISO 시각 |

`item_id`는 문자열로 넘어오는 값이라 `item_id_name`이 붙지 않는다. 금액은 모두 골드 정수다.

### collection (Harbor-Collection)

| type | 원본 이벤트 | player | data |
|---|---|---|---|
| `collection.registered` | `CollectionRegisteredEvent` | ✔ | `player`, `fish`, `fish_name?`, `scope`: `"total"`·`"season"`, `grade` |
| `collection.milestone` | `CollectionMilestoneEvent` | ✔ | `player`, `kind`: `"region_complete"`·`"total_percent"`, `key`: 지역 이름 또는 단계 키, `percent`: int, `title?`: 준 칭호 키 |
| `collection.record_broken` | `RecordBrokenEvent` | ✔ | `player`, `fish`, `fish_name?`, `league`: `"tier1"`·`"tier2"`·`"all"`, `scope`: `"personal"`·`"server"`, `size`: cm, `previous`: 이전 기록 cm (없으면 0) |
| `collection.aquarium_donated` | `AquariumDonatedEvent` | ✔ | `player`, `fish`, `fish_name?`, `grade`, `quality`: `{stars}`, `points` |
| `collection.aquarium_stage` | `AquariumStageChangedEvent` | – | `season`: int, `stage`: int |
| `collection.fish_released` | `FishReleasedEvent` | ✔ | `player`, `fish`, `fish_name?`, `grade`, `size` |

### fishing (Harbor-Fishing)

| type | 원본 이벤트 | player | data |
|---|---|---|---|
| `fishing.caught` | `FishCaughtEvent` | ✔이름 | `player`, `source`: `"rod"`·`"trap"`·`"boss"`, `fish`: [FishDef 요약](#fishdef-요약), `item?`: [FishItem](#fishitem), `region`, `region_name?`, `released`: bool (release_only 어종), `first_catch`: bool, `capped`: bool (직업 품질 상한에 걸림) |
| `fishing.trap_harvest` | `TrapHarvestEvent` | ✔이름 | `player`, `trap_id`, `region`, `region_name?`, `fish`: [FishItem](#fishitem)[] |

`fishing.caught`는 가장 자주 나는 이벤트다. 통발 수확은 `fishing.caught`를 내지 않는다. 디스코드 문구는 `fish.grade` → `<fish_grade>`처럼 평탄화된 이름을 쓴다.

### jobs (Harbor-Jobs)

| type | 원본 이벤트 | player | data |
|---|---|---|---|
| `jobs.advanced` | `JobAdvancedEvent` | ✔ | `player`, `tier`: int 1~3 (새 단계), `tier_key`: `"first"`·`"second"`·`"third"`, `line?`: `"explore"`·`"collect"`·`"produce"`·`"economy"` |
| `jobs.level_up` | `JobLevelUpEvent` | ✔ | `player`, `level` |
| `jobs.line_changed` | `JobLineChangedEvent` | ✔ | `player`, `from?`, `to` (계열 키) |
| `jobs.capstone_used` | `CapstoneUsedEvent` | ✔ | `player`, `node`: 스킬 ID, `node_name?` |

`tier`는 REST 요약의 `job_tier`와 같은 숫자(1~3)다. 바뀌지 않는 문자열 키가 필요하면 `tier_key`를 쓴다.

### npc (Harbor-Npc)

| type | 원본 이벤트 | player | data |
|---|---|---|---|
| `npc.reputation_tier` | `ReputationTierChangedEvent` | ✔ | `player`, `old_tier`, `new_tier`, `best_tier` (시즌을 넘어 유지되는 최고 등급) |
| `npc.affinity_level_up` | `AffinityLevelUpEvent` | ✔ | `player`, `npc`, `npc_name?`, `old_level`, `new_level` |
| `npc.gift_given` | `GiftGivenEvent` | ✔이름 | `player`, `npc`, `npc_name?`, `item?`, `item_name?`, `reaction`: `"love"`·`"like"`·`"normal"`·`"dislike"`, `points` |

### season (Harbor-Season)

| type | 원본 이벤트 | player | data | 비고 |
|---|---|---|---|---|
| `season.title_granted` | `TitleGrantedEvent` | ✔ | `player`, `title`, `title_name?`, `ref_id?` | 이미 가진 칭호면 안 난다 |
| `season.week_changed` | `SeasonWeekChangedEvent` | – | `season`: `{value}`, `previous_week`, `week` (13 이상은 연장 주) | 서버마다 |
| `season.daily_reset` | `DailyResetEvent` | – | `game_day`: `"YYYY-MM-DD"` | 서버마다 (기본 06:00) |
| `season.weekly_reset` | `WeeklyResetEvent` | – | `season`: `{value}`, `week` | 서버마다 (기본 월 06:00) |
| `season.hall_of_fame` | `HallOfFameUpdatedEvent` | – | `entry`: [HallOfFameEntry](#halloffameentry), `previous?`: HallOfFameEntry | 처리한 서버에서만 |
| `season.player_entered` | `PlayerSeasonEnteredEvent` | ✔이름 | `player`, `previous_season`: int (처음이면 0), `season`: `{value}`, `removed_stacks` | 새 시즌 첫 접속 |

### shop (Harbor-Shop)

| type | 원본 이벤트 | player | data |
|---|---|---|---|
| `shop.cosmetic_granted` | `CosmeticGrantedEvent` | ✔ | `player`, `cosmetic`, `cosmetic_name?`, `source`: string |
| `shop.donor_tier_changed` | `DonorTierChangedEvent` | ✔ | `player`, `previous`, `current`: `"none"`·`"harbor"`·`"fleet"` |
| `shop.season_pass_level_up` | `SeasonPassLevelUpEvent` | ✔ | `player`, `season`: int, `previous_level`, `level`, `bought`: bool |
| `shop.festival_progress` | `FestivalProgressEvent` | – | `festival`, `festival_name?`, `total_krw`, `goal_krw`, `reached`: bool |

`shop.festival_progress`는 Redis로 축제 게이지를 받은 서버마다 날 수 있다 (원본 이벤트 설명: "harbor:festival received").

### boss (Harbor-Boss)

| type | 원본 이벤트 | player | data |
|---|---|---|---|
| `boss.encounter_start` | `BossEncounterStartEvent` | – | `encounter`: [EncounterInfo](#encounterinfo), `forced`: bool |
| `boss.encounter_end` | `BossEncounterEndEvent` | – | `boss`, `boss_name?`, `encounter`: EncounterInfo, `result`: `"cleared"`·`"failed"`·`"aborted"`, `cleared`: bool, `type`: `"intro"`·`"sea"`·`"dimension"`·`"raid"`·`"world"`, `participants`: UUID[] (파티장 먼저), `contributions`: [Contribution](#contribution)[] (순위순), `fish?`, `fish_name?`, `release_only`: bool, `duration_ms`, `story`: bool |
| `boss.world_progress` | `WorldBossProgressEvent` | – | `status`: [WorldBossStatus](#worldbossstatus), `stage`: 1(75%↓)·2(50%↓)·3(25%↓) |

`boss.encounter_start`는 취소 가능한 이벤트라, 다른 플러그인이 취소하면 나가지 않는다.

### world (Harbor-World)

| type | 원본 이벤트 | player | data | 비고 |
|---|---|---|---|---|
| `world.sea_event_warn` | `SeaEventWarnEvent` | – | `sea_event`: [ActiveSeaEvent](#activeseaevent) | 서버마다 |
| `world.sea_event_start` | `SeaEventStartEvent` | – | `sea_event` | 서버마다 |
| `world.sea_event_end` | `SeaEventEndEvent` | – | `sea_event` | 서버마다 |
| `world.treasure_claimed` | `TreasureClaimedEvent` | ✔이름 | `player`, `region`, `region_name?`, `site_id`, `tier`: string, `gold`, `hint`: bool | |
| `world.ship_wrecked` | `ShipWreckedEvent` | ✔이름 (배 주인) | `player`, `ship`: [ShipInfo](#shipinfo) | |
| `world.region_first_visit` | `RegionFirstVisitEvent` | ✔이름 | `player`, `region`, `region_name?`, `info`: [RegionInfo](#regioninfo) | 시즌 첫 방문 |
| `world.plot_liked` | `PlotLikedEvent` | ✔이름 (좋아요 누른 사람) | `player`, `plot`: [PlotInfo](#plotinfo), `likes` | |
| `world.chart_progress` | `ChartProgressEvent` | ✔이름 | `player`, `region`, `region_name?`, `percent`: number, `step`: int (방금 받은 보상 단계, 없으면 0) | |

해상 이벤트는 모든 서버가 같은 사건을 따로 발행한다. 한 번만 처리하려면 `data.sea_event.run_id`로 거른다.

### 공통 객체

#### FishDef 요약
`{id, no, name, grade}`. `grade`: `common`·`uncommon`·`rare`·`epic`·`legendary`·`mythic`.

#### FishItem
`HarborItem.Fish` 레코드:
`{species, species_name?, size_cm, quality: {stars}, catcher: {uuid, name}, season: {value}, caught_at, uid?, version}`. `uid`는 ★5·전설·신화 물고기에만 있다 (fish_ledger에 기록되는 고유 ID).

#### QuestRewardSummary
`{gold, mulbineul, exp, reputation, items: string[], title?, chronicle?}`

#### HallOfFameEntry
`{season, category, subject, rank, player, name_at_time, value, detail: {string: string}, recorded_at, finalized: bool}`

#### EncounterInfo
`{id, boss, boss_name?, state, leader, members: UUID[], roles: {<uuid>: role}, server, slot, hp_ratio, hull, hull_max, elapsed_ticks}`
- `state`: `preparing`·`intro`·`hook`·`counter`·`final_tug`·`cleared`·`failed`·`aborted`
- `role`: `anchor`·`harpooner`·`captain`·`mechanic`·`quartermaster`

#### Contribution
`{player, role, damage, tension, repair, evasion, score, rank, top: bool, eligible: bool}`

#### WorldBossStatus
`{boss, boss_name?, event_id, stage, percent, participants, server, state}`

#### ActiveSeaEvent
`{run_id, event_id, event_id_name?, name, type, region?, region_name?, area?, starts_at, ends_at, phase, wildlife_boost, fish_effects: [{type, fish, mult, chance, cap}]}`
- `type`: `storm`·`ghost_ship`·`migration`·`tournament`·`special`
- `phase`: `warn`·`active`

#### ShipInfo
`{ship_id, owner, type, type_name?, name, upgrades: {speed|cargo|hull: int}, durability, max_durability, state, flags: string[]}`
- `state`: `stored`·`afloat`·`wrecked`, `flags` 원소: `ice_break`·`storm`·`ocean`

#### RegionInfo
`{id, id_name?, name, world, biome, order, rec_level, hourly_gold, areas: string[], server?}`

#### PlotInfo
`{plot_id, owner, owner_name, grid_x, grid_z, size, theme, visit_policy, likes, world, min_x, min_z, max_x, max_z, spawn_x, spawn_y, spawn_z, spawn_yaw, members: UUID[]}`
- `visit_policy`: `public`·`members`·`private`

### 다른 플러그인이 더하는 이벤트

```java
Harbor.api().events().emit("skript.lottery_won", player.getUniqueId(), player.getName(), Map.of("prize", 1000));
```

data 값은 문자열·숫자·불리언·UUID·리스트·Map만 그대로 들어가고, enum은 소문자 이름, 그 밖은 `toString()`이 된다 (Core `CoreExternalEvents.normalize`). 이 경우 `player`·`playerName`은 넘긴 값 그대로다.

## 4. Redis 직접 구독

```bash
redis-cli -h <redis> SUBSCRIBE harbor:events
```

```ts
// Node.js (ioredis)
import Redis from "ioredis";

const sub = new Redis(process.env.REDIS_URL!);
await sub.subscribe("harbor:events");
sub.on("message", (_channel, raw) => {
  const env = JSON.parse(raw);
  if (env.type !== "ExternalEvent") return;
  const ev = env.payload; // { id, type, version, server, occurredAt, player?, playerName?, data }
  if (ev.version !== 1) return; // 모르는 버전은 건너뛰거나 따로 처리
  handle(ev);
});
```

- pub/sub이라 구독하지 않던 동안의 메시지는 받을 수 없다. 놓치면 안 되는 처리는 REST `/v1/events` 폴링과 함께 쓰거나, 게임 DB에서 다시 확인한다.
- Redis는 서버·채팅·세션 등에도 쓰는 내부 인프라다. 외부에 열지 말고 읽기 전용 계정(ACL로 `harbor:events` SUBSCRIBE만)을 쓴다.

## 5. 웹훅

### 설정 (`plugins/Harbor-Web/config.yml`)

```yaml
webhooks:
  enabled: true
  retries: 3            # 실패 시 재시도 횟수 (첫 시도 제외)
  queue_size: 1000      # 대상별 대기열 크기 (최소 10)
  timeout_seconds: 10   # 연결·요청 타임아웃
  targets:
    - name: my-backend
      url: https://example.com/harbor/events
      format: json
      secret: change-me
      events: ["*"]
    - name: discord-announce
      url: https://discord.com/api/webhooks/…
      format: discord
      events: [quest.chapter_completed, collection.record_broken, fishing.caught]
      where: {fish.grade: legendary}
```

| 키 | 기본 | 설명 |
|---|---|---|
| `name` | `webhook<순번>` | 대상 이름 (로그, `/harbor web test`) |
| `url` | (필수) | 비어 있으면 그 대상을 건너뛴다 |
| `format` | `discord` | `json`(대소문자 무시)이 아니면 모두 `discord`로 처리한다 |
| `secret` | `""` | `json` 서명 키. 비면 서명 헤더를 보내지 않는다 |
| `events` | `["*"]` | type glob 목록. 하나라도 맞으면 보낸다. `*`만 와일드카드 (`fishing.*`, `*.record_broken`) |
| `where` | `{}` | data 조건. **모두** 맞아야 보낸다 |

**`where` 비교 방식** (`WebhookRules.accepts`):
- data를 점으로 이은 경로로 평탄화한다: `{fish: {grade: "legendary"}}` → `fish.grade = "legendary"`. 리스트는 `"a, b"`처럼 쉼표+공백으로 이은 글 하나다.
- 설정 값도 글로 바꿔서 **문자열이 정확히 같을 때만** 맞는다 (대소문자 구분).
- 정수인 실수는 소수점 없이 비교한다 (`5.0` → `"5"`). 불리언은 `"true"`/`"false"`.
- 그 경로가 없으면 맞지 않는다.

### 누가 보내는가 (리더 잠금)

- `webhooks.enabled`가 켜진 서버는 모두 `harbor:events`를 구독하지만, Redis 잠금 `harbor:lock:webhook`(TTL 30초, 10초마다 연장)을 잡은 **한 서버만** 대기열에 넣는다.
- 리더 서버가 죽으면 잠금이 만료될 때까지(최대 약 30초) + 다음 확인 주기(최대 10초) 동안 아무도 보내지 않는다. 그 사이 이벤트는 사라진다.
- 리더가 아닌 서버는 받은 이벤트를 버린다 (나중에 보내지 않는다).

### 요청 형식

모든 웹훅 요청은 `POST <url>`, 헤더 `Content-Type: application/json`, `User-Agent: Harbor-Web`이다.

#### `json`

본문은 이벤트 하나를 [snake_case 형식](#rest웹훅-형식-snake_case)으로 쓴 JSON이다 (REST `/v1/events`의 `event`와 같다). 봉투는 붙지 않는다.

```http
POST /harbor/events HTTP/1.1
Content-Type: application/json
User-Agent: Harbor-Web
X-Harbor-Event: collection.record_broken
X-Harbor-Id: a8d2b7c4-0f7e-4b39-8a51-1b6f2d3e4c55
X-Harbor-Timestamp: 1763640006
X-Harbor-Signature: sha256=5d1f0c…(64자 hex)

{"id":"a8d2b7c4-0f7e-4b39-8a51-1b6f2d3e4c55","type":"collection.record_broken","version":1,"server":"main-1","occurred_at":"2026-11-20T12:00:05.901Z","player":"069a79f4-44e9-4726-a5be-fca90e38aaf5","player_name":"Steve","data":{"scope":"server","fish":"harbor:fish/harbor_mackerel","size":41.2,"league":"all","fish_name":"항구 고등어","previous":39.8,"player":"069a79f4-44e9-4726-a5be-fca90e38aaf5"}}
```

| 헤더 | 값 |
|---|---|
| `X-Harbor-Event` | 이벤트 type |
| `X-Harbor-Id` | 이벤트 id (재시도해도 같다) |
| `X-Harbor-Timestamp` | 그 **요청을 보낸** 시각, 유닉스 초 (10진 정수 글). 재시도마다 새로 찍는다 |
| `X-Harbor-Signature` | `secret`이 있을 때만. `sha256=` + 소문자 hex |

본문 직렬화에 관한 사실 (서명 검증 때 중요):
- 필드 순서는 `id, type, version, server, occurred_at, player, player_name, data`. 값이 없는 `player`/`player_name`은 빠진다. data 키 순서는 정해져 있지 않다.
- HTML 문자(`<` `>` `&` `=` `'`)를 이스케이프하지 않고 그대로 쓴다 (REST 응답과 같다). 한글도 그대로 UTF-8이다.
- data의 정수는 [숫자 표기](#숫자-표기)대로 정수로 나온다.
- 그러므로 서명은 **받은 원본 바이트**로 검증한다. JSON을 파싱했다가 다시 직렬화한 값으로 검증하면 틀린다.

#### `discord`

```json
{"content":"🏆 **Steve**님이 항구 고등어 서버 기록을 갱신했습니다! 41.2cm (리그 all)","allowed_mentions":{"parse":[]}}
```

- 문구는 `messages_ko.yml`의 `web.discord.<type>` (type의 점이 YAML 단계가 된다: `web.discord.collection.record_broken`). **문구가 없는 type은 보내지 않는다.**
- MiniMessage로 렌더한 뒤 평문으로 바꾸므로 색 태그는 사라진다. 2000자를 넘으면 1997자 + `...`로 자른다.
- `allowed_mentions.parse = []`로 모든 멘션을 막는다.
- 자리표시자:
  - `<player>`: `playerName`, 없으면 `player` UUID, 둘 다 없으면 `-`. 모듈 이벤트도 발행 서버가 이름을 찾아 넣으므로 보통 이름이 찍힌다 (players 행이 없을 때만 UUID).
  - `<server>`, `<type>`
  - data의 평탄화된 모든 칸: 점은 밑줄로, `[a-z0-9_-]` 밖의 글자도 밑줄로 바꾼다 (`fish.name` → `<fish_name>`). data에 `player`·`server`·`type` 칸이 있어도 위 세 값이 우선한다. 값은 글 그대로 넣는다 (태그 해석 안 함). 정수는 소수점 없이 들어간다 (`jobs.advanced`의 `<tier>` → `2`, 문구 "2차 전직").
- X-Harbor-* 헤더와 서명은 붙지 않는다.

현재 문구가 있는 type (`content/messages_ko.yml`):
`server.started` `server.stopping` `server.webhook_test` `quest.completed` `quest.chapter_completed` `collection.record_broken` `collection.milestone` `collection.aquarium_stage` `fishing.caught` `jobs.advanced` `boss.encounter_end` `world.sea_event_start` `economy.auction_sold` `economy.trade_flagged` `season.title_granted`

### 서명 (HMAC-SHA256)

```
signed   = X-Harbor-Timestamp + "." + <요청 본문 원본 바이트(UTF-8)>
signature = "sha256=" + lowercase_hex( HMAC_SHA256( key = UTF-8(secret), message = signed ) )
```

받는 쪽 절차:
1. `X-Harbor-Timestamp`와 `X-Harbor-Signature` 헤더를 읽는다. 없으면 거절한다 (secret을 설정한 대상인 경우).
2. 위 식으로 계산한 값과 헤더 값을 **고정 시간 비교**한다.
3. timestamp가 현재 시각과 너무 다르면(권장: ±300초) 거절한다. 보내는 쪽은 이 검사를 하지 않으므로 받는 쪽 책임이다. 재시도는 timestamp를 새로 찍으므로 재시도 때문에 거절되지는 않는다.
4. `X-Harbor-Id`(= 본문 `id`)로 중복을 거른다.

#### Node.js / TypeScript (Express)

```ts
import crypto from "node:crypto";
import express from "express";

const SECRET = process.env.HARBOR_WEBHOOK_SECRET!;
const TOLERANCE_SEC = 300;
const seen = new Set<string>(); // 운영에서는 TTL 있는 저장소(Redis 등)로

function verify(rawBody: Buffer, timestamp: string | undefined, signature: string | undefined): boolean {
  if (!timestamp || !signature || !/^\d+$/.test(timestamp)) return false;
  if (Math.abs(Date.now() / 1000 - Number(timestamp)) > TOLERANCE_SEC) return false;
  const expected =
    "sha256=" +
    crypto
      .createHmac("sha256", SECRET)
      .update(timestamp + ".")
      .update(rawBody)
      .digest("hex");
  const a = Buffer.from(expected, "utf8");
  const b = Buffer.from(signature, "utf8");
  return a.length === b.length && crypto.timingSafeEqual(a, b);
}

const app = express();
// 원본 바이트가 필요하므로 express.json()이 아니라 raw를 쓴다
app.post("/harbor/events", express.raw({ type: "application/json" }), (req, res) => {
  const ok = verify(req.body as Buffer, req.header("X-Harbor-Timestamp"), req.header("X-Harbor-Signature"));
  if (!ok) return res.status(401).end();

  const event = JSON.parse((req.body as Buffer).toString("utf8"));
  if (seen.has(event.id)) return res.status(204).end(); // 중복
  seen.add(event.id);

  // 2xx를 빨리 돌려주고 무거운 처리는 비동기로 (보내는 쪽 타임아웃 기본 10초)
  res.status(204).end();
  queueMicrotask(() => handle(event));
});
```

#### Python (FastAPI)

```python
import hashlib
import hmac
import json
import os
import time

from fastapi import FastAPI, Header, HTTPException, Request, Response

SECRET = os.environ["HARBOR_WEBHOOK_SECRET"].encode("utf-8")
TOLERANCE_SEC = 300
seen: set[str] = set()  # 운영에서는 TTL 있는 저장소로

app = FastAPI()


def verify(raw: bytes, timestamp: str | None, signature: str | None) -> bool:
    if not timestamp or not signature or not timestamp.isdigit():
        return False
    if abs(time.time() - int(timestamp)) > TOLERANCE_SEC:
        return False
    mac = hmac.new(SECRET, timestamp.encode("utf-8") + b"." + raw, hashlib.sha256)
    expected = "sha256=" + mac.hexdigest()
    return hmac.compare_digest(expected, signature)


@app.post("/harbor/events")
async def harbor_events(
    request: Request,
    x_harbor_timestamp: str | None = Header(default=None),
    x_harbor_signature: str | None = Header(default=None),
):
    raw = await request.body()  # 원본 바이트로 검증
    if not verify(raw, x_harbor_timestamp, x_harbor_signature):
        raise HTTPException(status_code=401)
    event = json.loads(raw)
    if event["id"] in seen:
        return Response(status_code=204)
    seen.add(event["id"])
    # handle(event) — 무거운 처리는 백그라운드로
    return Response(status_code=204)
```

### 전달 정책 (`WebhookDispatcher`)

- **대기열:** 대상마다 대기열 하나(`queue_size`, 최소 10)와 가상 스레드 하나. 대상끼리는 서로 막지 않는다. 대기열이 가득 차면 **새 이벤트를 버리고** 경고를 남긴다 (오래된 것을 밀어내지 않는다).
- **순서:** 한 대상 안에서는 대기열 순서대로 하나씩 보낸다 (재시도 중이면 뒤 이벤트는 기다린다). 여러 서버의 이벤트가 Redis에서 섞이는 순서는 보장하지 않으므로, 순서가 중요하면 `occurred_at`으로 정렬한다 (서버마다 시계가 다를 수 있음). 이름을 DB에서 찾느라 늦게 발행된 이벤트는 같은 서버의 다른 이벤트보다 뒤에 올 수 있다.
- **성공:** HTTP 2xx. 응답 본문은 보지 않는다.
- **재시도:** 최대 `retries`번(기본 3, 즉 총 4번 시도). 시도 사이 대기:

  | 실패한 시도 | 1번째 | 2번째 | 3번째 | 4번째 | n번째 (n≥3) |
  |---|---|---|---|---|---|
  | 대기 | 1초 | 5초 | 15초 | 30초 | 15초×(n−2) |

  - 429: 응답 JSON의 `retry_after`(초, 실수 가능. 디스코드 형식) → 없으면 `Retry-After` 헤더(정수 초만, HTTP 날짜 형식은 무시) → 둘 다 없으면 위 표.
  - 429가 아닌 4xx: **재시도하지 않고** 실패 처리한다 (본문 앞 200자를 로그에 남김).
  - 5xx, 3xx(리다이렉트를 따라가지 않음), 연결 실패, 타임아웃: 재시도.
  - 마지막 시도가 실패한 뒤에도 그 대기 시간만큼 기다렸다가 다음 이벤트로 넘어간다 (구현 특성).
- **타임아웃:** 연결과 요청 모두 `timeout_seconds`(기본 10초).
- **실패한 이벤트:** 재시도를 다 쓰면 버린다 (`failed` 통계만 오른다). 다시 보내는 저장소는 없다.
- **멱등성:** 응답을 못 받은 시도(타임아웃 등)도 재시도하므로 **같은 이벤트가 두 번 이상 올 수 있다.** `X-Harbor-Id`로 거른다. "서버마다" 나는 이벤트는 id가 서로 달라 id로는 걸러지지 않는다 (카탈로그 비고 참고).
- **전달 보장:** 최대 한 번에 가깝다 (at-most-once + 재시도). 리더 교체 중, 대기열 가득 참, 서버 재시작(대기열은 메모리) 때 잃을 수 있다.
- **상태 확인:** `/harbor web status` → 리더 여부와 `sent`·`failed`·`dropped` 수.
- **시험:** `/harbor web test <대상>`은 `server.webhook_test` 이벤트를 그 대상 대기열에 바로 넣는다. `events`·`where` 필터와 리더 여부를 보지 않는다. `json` 대상이면 서명도 붙는다. 이 이벤트의 `data.target`은 문자열이다 (Redis를 거치지 않는다).

## 6. REST로 최근 이벤트 읽기

`GET /v1/events?after=<seq>&type=<glob>&limit=<1~500>` — 자세한 내용은 [api.md](api.md#get-v1events)와 [openapi.yaml](openapi.yaml).

- REST 서버(`http.enabled`)가 Redis에서 받은 이벤트를 메모리에 최근 `http.recent_events`개(기본 500, 최소 10) 보관한다. **seq와 커서는 서버마다 따로**이고(응답의 `server`가 seq를 붙인 서버), 재시작하면 비워지며 seq도 1부터 다시 시작한다 (그때 `next`가 0).
- 이벤트는 [snake_case 형식](#rest웹훅-형식-snake_case)이다.
- `server.webhook_test`는 Redis를 거치지 않으므로 여기에 나오지 않는다.
- 웹훅 리더 잠금과 무관하게, REST가 켜진 서버면 받는다.
