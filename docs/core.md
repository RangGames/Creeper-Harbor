# Harbor-Core · Harbor-Common

모든 Purpur 서버에 들어가는 공통 플러그인. 콘텐츠·플레이어 데이터·재화 원장·서버 이동 세션·인벤토리 동기화·문구·리그·가구·리소스팩을 맡고, 게임 모듈에 `Harbor.api()`로 서비스를 준다.

- **Harbor-Common:** Paper·Velocity API를 쓰지 않는 순수 Java 라이브러리다. DB 계층·원장·Redis·세션 잠금·콘텐츠 로더·리그 수학·팩 빌더가 있다. Core와 Proxy jar 안에 shadow로 들어가고, harbor-tools도 같은 로더를 쓴다.
- **Harbor-Core:** Purpur 플러그인이다. Common을 품고 있고, 게임 모듈은 Core가 켜진 뒤 Core의 클래스패스에 붙어 켜진다.

## 주요 기능

플레이어가 느끼는 것:

- **서버를 옮겨도 그대로인 데이터:** 로비·본 서버·인스턴스가 같은 인벤토리·엔더 상자·PDC·경험치·체력·배고픔·효과를 쓴다.
- **골드 잔액과 순위:** `/골드`, `/골드 순위`
- **명령 도움말:** `/도움말`. `content/help.yml` 순서대로 보여 주고, 그 서버에 없거나 권한이 없는 명령은 감춘다. 줄을 누르면 실행하거나 입력창에 넣는다.
- **서버 간 채팅:** 채팅은 프록시를 거쳐 모든 서버에 보인다. 줄 형식은 `messages_ko.yml`의 `chat.format`·`chat.party_format`으로 이 서버에서 그린다. PlaceholderAPI가 있으면 `%…%`도 쓸 수 있다. `!`로 시작하면 파티원에게만 간다 (Boss 파티). Redis가 끊기면 이 서버 안에서만 보인다.
- **잠수 시 로비로:** 본 서버(main)에서 움직임·시점·입력·채팅·명령·클릭이 없으면 경고 뒤 로비로 보낸다. 자리는 유예된다 (`harbor.afk.exempt` 제외).
- **가구·커스텀 블록:** 가구 아이템을 들고 블록을 우클릭하면 놓이고, 웅크리고 우클릭하면 회수한다. 앉을 수 있는 가구도 있다.
- **리소스팩:** 로비 접속 때 필수 리소스팩을 받는다.

모듈에 제공하는 것 (`wiki.creeper.harbor.core.api`, 진입점 `Harbor.api()`):

| 서비스 | 하는 일 |
|---|---|
| `ContentService` | 콘텐츠 스냅샷 (변경 불가, 아무 스레드에서 읽어도 됨) |
| `PlayerService` · `HarborPlayer` · `PlayerComponent<T>` | 플레이어 데이터. 모듈은 컴포넌트를 붙이고 값은 변경 불가 객체로 교체한다. 5분 자동 저장, `saveNow` |
| `CurrencyService` | 골드·물비늘 증감 (원장 경유) |
| `ItemService` · `HarborItem` · `ItemModels` | Harbor 아이템 만들기·읽기, 리소스팩에 있는 모델만 쓰기 |
| `ModifierService` · `Modifier` · `StatKey` | 수치 보정 (ADD 합 → MULTIPLY 곱 → `stats.<키>.min/max` 클램프) |
| `MessageService` | `messages_ko.yml` 키 + MiniMessage |
| `FlagService` | 플레이어 플래그(`player_flags`)·서버 플래그(`server_flags`). 바뀌면 모든 서버에 알림 |
| `Ui` · `Dialogs` | 상자 GUI, Dialog 화면(알림·선택지·입력) |
| `ProxyBridge` · `HandoffBlocker` | 서버 이동 요청, 이동 막기 |
| `WorldBorderService` | 화면 가장자리 경고 연출의 우선순위 중재 |
| `RigService` · `RigInstance` | 디스플레이 리그 재생. `RigOptions.withHitboxEvents()`·`RigInstance.callBukkitEvents()`를 쓰면 히트박스 클릭·애니메이션 이벤트가 Bukkit 이벤트로도 나온다 (Skript용) |
| `FurnitureService` | 가구·커스텀 블록. 아이템 외에 위치에 놓기(`place`·`placeAs`, 플레이어와 같은 공간·보호 검사), 찾기(`furnitureAt`·`placedOf`·`furnitureNear`·`furnitureIn` → `Placed`: ID·위치·놓은 사람·히트박스), 치우기(`remove`, 아이템 돌려받기·떨어뜨리기) |
| `HarborSounds` | `sounds.yml` 표로 소리 재생 (팩이 없으면 바닐라 대체음) |
| `MailService` · `PartyService` | 인터페이스만 Core에 있고 구현은 Economy(우편함)·Boss(파티). `PartyService`는 조회 외에 `create`·`invite`·`accept`·`leave`·`kick`·`disband`·`promote`·`setRole`(`/파티`와 같은 규칙·알림, 결과는 `Result`)도 있다 |
| `ExternalEvents` | 외부 연동 이벤트 발행 (아래 참고) |
| `database()` · `writeBehind()` · `bus()` · `locks()` · `redis()` | DB·지연 쓰기 큐·Redis 메시지 버스·분산 잠금 |

게임 모듈 메인 클래스는 `HarborModule`을 상속하고 `enable(ModuleContext)`에서 콘텐츠 형식, 플레이어 컴포넌트, `/harbor` 하위 명령, DB 마이그레이션을 등록한다.

## 플레이어 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/도움말` | (없음) | 쓸 수 있는 명령 목록 (`help.yml`) |
| `/골드` | `harbor.gold` | 내 골드 잔액 |
| `/골드 순위 [페이지]` | `harbor.gold` | 골드 순위 |

## 관리자 명령

`/harbor`는 `harbor.admin`, 하위 명령은 `harbor.admin.<하위 명령>`이 필요하다. 모든 실행은 `admin_log`에 남는다.

| 명령 | 권한 | 설명 |
|---|---|---|
| `/harbor reload [all\|content\|balance\|messages]` | `harbor.admin.reload` | 콘텐츠 다시 읽기. 오류가 하나라도 있으면 거부하고 기존 스냅샷을 유지한다 |
| `/harbor validate` | `harbor.admin.validate` | 콘텐츠 검사만 |
| `/harbor give <플레이어> <아이템\|어종> [개수]` | `harbor.admin.give` | Harbor 아이템 지급 |
| `/harbor give <플레이어> <어종> <크기> <품질>` | `harbor.admin.give` | 크기·품질을 정해 어종 지급 |
| `/harbor stat <플레이어> <스탯>` | `harbor.admin.stat` | 보정자 적용 결과 확인 |
| `/harbor player <플레이어>` | `harbor.admin.player` | 플레이어 데이터 요약 |
| `/harbor currency <플레이어> give\|take gold\|mulbineul <양>` | `harbor.admin.currency` | 재화 지급·회수 (원장 `ADMIN_GRANT`·`ADMIN_TAKE`) |
| `/harbor debug` | `harbor.admin.debug` | DB 풀·지연 쓰기 큐·접속자·스레드 |
| `/harbor inventory history <플레이어>` | `harbor.admin.inventory` | 인벤토리 기록 (번호·시각·이유·서버) |
| `/harbor inventory view <플레이어> [번호]` | `harbor.admin.inventory` | 지금 또는 기록 시점 인벤토리를 읽기 전용 창으로 |
| `/harbor inventory restore <플레이어> <번호>` | `harbor.admin.inventory` | 기록으로 되돌림. 이 서버 접속 중이면 바로, 오프라인이면 DB, 다른 서버 접속 중이면 거부 |
| `/harbor furniture list` · `give <대상> <가구> [개수]` | `harbor.admin.furniture` | 가구 목록·지급 |
| `/harbor block list` · `give <대상> <블록> [개수]` | `harbor.admin.block` | 커스텀 블록 목록·지급 |
| `/harbor rig list` · `spawn <리그>` · `play <동작> [once]` · `clear [반경]` · `stats` | `harbor.admin.rig` | 리그 시험 소환·재생·정리·부하 |
| `/harbor fish list [지역]` · `give <대상> [지역]` · `spawn <어종> [동작]` · `showcase [지역]` · `play <동작\|special> [반경]` · `clear` | `harbor.admin.fish` | 어종 모델·애니메이션 시연 |
| `/harbor pack` · `/harbor pack build` | `harbor.admin.pack` | 리소스팩 상태, 다시 빌드 |
| `/골드 <플레이어>` | `harbor.admin.gold` | 다른 사람 잔액 |
| `/골드 지급\|회수\|설정 <플레이어> <양>` | `harbor.admin.gold` | 골드 조정 |

그 밖의 권한:

| 권한 | 기본 | 뜻 |
|---|---|---|
| `harbor.admin.alerts` | op | 원장 불일치 등 운영 알림 받기 |
| `harbor.furniture.manage` | op | 남이 놓은 가구도 회수 |
| `harbor.afk.exempt` | false | 잠수해도 로비로 보내지 않음 |

## 재화와 원장

- 골드·물비늘은 `CurrencyService`(→ Common `LedgerWriter`)로만 바뀐다. 한 트랜잭션 안에서 잔액 행 잠금 → 음수 거부 → 원장 기록 → 잔액 갱신을 한다.
- 원장의 참조 ID는 유니크라서, 같은 지급이 두 번 들어와도 한 번만 반영된다.
- 사유는 Common `LedgerReason` enum에 있는 것만 쓸 수 있다 (예: `FISH_SELL`, `QUEST_REWARD`, `BOSS_REWARD`, `SEASON_RESET`). 새 사유는 enum에 추가해야 한다.
- 매일 원장 합계와 잔액을 대조한다 (`LedgerAuditJob`). 맞지 않으면 `harbor.admin.alerts` 보유자에게 알린다. 그래서 잔액 열을 직접 UPDATE하면 안 된다.
- 시즌 초기화도 `SEASON_RESET` 원장 기록으로 잔액을 0으로 만든다.
- 재화가 바뀌면 `CurrencyChangedEvent`가 난다.

## 서버 이동 세션과 인벤토리 동기화

- **세션 잠금:** Redis `harbor:lock:<uuid>`(값 `OWNED|서버` 또는 `HANDOFF|출발|도착`, 30초 TTL). 도착 서버는 접속 전 단계에서 handoff를 확인하기 전까지 플레이어를 불러오지 않는다. Redis 장애 때는 이동을 막는다 (복제 방지 우선).
- **플레이어 데이터:** 접속 전에 모든 컴포넌트를 병렬로 불러온다. 하나라도 실패하면 접속을 거부한다. 퇴장·서버 이동 때는 그 플레이어 것만 즉시 쓴다.
- **인벤토리 동기화:** 인벤토리·엔더 상자·PDC·경험치·체력·배고픔·효과를 `player_inventory`에 저장한다. 접속 전 로드 → 접속 직후 적용. DB 행이 없는 플레이어는 본 서버(main)의 플레이어 파일이 원본이다.
- **기록:** 퇴장·이동·사망·복구 때 기록을 남기고, 플레이어마다 `inventory.history_keep`개를 보관한다. `/harbor inventory`로 보고 되돌린다.
- **거래 뒤 저장:** 재화가 커밋되거나 경제 거래(판매·구매·경매·우편·제작)가 끝나면 `PlayerService.saveSoon`으로 인벤토리를 바로 저장한다. 서버가 거래 직후 죽어도 재화와 아이템이 어긋나지 않게 한다.
- **맡긴 아이템:** 자동 저장·거래 뒤 저장은 창을 닫지 않으므로 커서·제작 칸 아이템과 모듈이 `ModuleContext.itemCustody`로 알려 준 아이템(판매 상자, 수족관 기부함)을 저장본 인벤토리(넘치면 엔더 상자)에 넣어 둔다. 서버가 죽어도 인벤토리로 돌아온다.
- **트랜잭션 우편:** `MailService.sendItemInTx`는 호출한 쪽 트랜잭션 안에서 우편을 넣는다 (통발 자동 회수가 행 삭제와 함께 쓴다). 커밋 뒤 `notifyArrived`를 부른다.
- **이동 중 잠금:** 서버 이동용 데이터를 뜬 뒤 접속이 넘어갈 때까지 드롭·줍기·상호작용·인벤토리·피해·명령어를 막는다 (HuskSync의 locked와 같음). 이동 전 훅을 기다리던 중에 접속이 끊기면 일반 퇴장처럼 저장하고, 훅이 4초 안에 끝나지 않으면 이동을 취소한다. 모듈은 `PlayerService.frozen`이 참이면 아이템을 인벤토리 대신 우편으로 보낸다.

## 콘텐츠 시스템

- 콘텐츠는 `plugins/Harbor/content/` 아래 YAML이다. 형식은 두 가지다. `Entries`는 ID가 있는 항목 목록이고, `Single`은 파일 하나다.
- 읽을 때 오류를 줄·열 위치와 함께 모은다. 다른 콘텐츠를 가리키는 ID는 한꺼번에 존재 여부를 검사한다. 서버 API가 있어야 하는 검사(바닐라 아이템 이름 등)는 서버에서만 한다.
- 오류가 하나라도 있으면 시작을 멈추고(첫 로드), 리로드는 거부한다(기존 스냅샷 유지).
- **기본 콘텐츠 (`DefaultContent`):** Core jar 안에 저장소의 `content/`가 들어 있다. `content_dir`을 비워 기본 폴더를 쓰면 시작할 때마다 다음을 한다.
  - 없는 파일은 만든다.
  - 서버에서 고치지 않은 파일은 새 기본값으로 바꾼다. 기본값에서 빠진 파일은 지운다.
  - 서버에서 고친 파일은 그대로 두고 경고한다. 새 기본값에 생긴 항목만 채워 넣는다.
  - 기록은 `content/.harbor-defaults`에 남는다.
- `content_dir`을 따로 지정하면 그 폴더는 건드리지 않는다.

## 콘텐츠 파일 (Core 소유)

| 파일 | 내용 | 주요 필드 |
|---|---|---|
| `fish/*.yml` | 어종 | `id` `no` `name` `lore` `grade` `region` `size_cm` `base_price` `model` `season` `time` `weather` `moon` `area` `bait` `job_tier_min` `unlock_flag` `release_only` `weight` |
| `items/*.yml` | 아이템 (낚싯대·줄·찌·릴·미끼·통발·선박 증서·도구·장비·음식·재료 등) | `id` `name` `lore` `type` `grade` `base_item` `model` `stats` `buy_price` `sell_price` `tradeable` `season_reset` `region_access` |
| `regions.yml` | 지역 | `id` `name` `world` `bounds` `biome` `fishing_spots` `rec_level` `trap_zone` `unlock` `music` |
| `balance.yml` | 모든 수치 | 모듈별 절 (아래) |
| `messages_ko.yml` | 모든 문구 | MiniMessage |
| `rigs/*.yml`, `rigs/fish/*.yml` | 디스플레이 리그 (뼈대·부품·동작) | `bones` `animations` `default_animation` `forward`. `rigs/fish/`는 `rigImport`로 만든다 |
| `resource_pack.yml` | 리소스팩에 있는 모델·소리 목록 | 손으로 고치지 않는다 (`packIndex`로 생성) |
| `sounds.yml` | 소리 이벤트 표 | `source` `volume` `pitch` `loop` `fallback` |
| `help.yml` | `/도움말` 목록 | `sections[].commands[]`: `command` `text` `run` |
| `furniture/furniture.yml` | 가구 | `model` `base_item` `width` `height` `seat` `solid` `light` `rotation` `scale` `offset_y` |
| `furniture/blocks.yml` | 커스텀 블록 (노트블록 상태) | `state`(1~399, 바꾸지 않음) `texture`/`top`·`side`/`model`/`textures` `rotation` `hardness` `tool` `tier` `drops` `silk_touch` `exp` |

## 설정 값

`balance.yml`에서 Core가 읽는 절:

| 절 | 내용 |
|---|---|
| `stats` | 보정자 스탯 기본값·하한·상한 (`/harbor stat`) |
| `items` | `custom_models`, `fish_base_item` |
| `world_alias` | 개발 서버의 월드 이름을 지역 월드에 대응 (운영 서버는 비움) |
| `inventory` | `sync`(모든 서버가 같은 값이어야 함), `history_keep`, `commit_save_delay_ticks`(거래 커밋 뒤 인벤토리 즉시 저장까지 지연) |
| `afk` | `lobby_minutes`(0이면 끔), `warn_seconds` |
| `furniture` | `fix_glitched_noteblocks`: 번호 배정 전부터 있던 노트블록이 커스텀 모양으로 보이면 바닐라로 되돌림 |
| `rig` | `fps`(20의 약수여야 함), `max_parts`, `lod_distance`, `tick_budget_ms`, `part_budget` 등 |
| `sounds` | `custom`: 리소스팩 소리 사용 여부 |

서버 설정은 `plugins/Harbor/harbor-infra.yml`이다 ([README](README.md#서버-설정-기본-harbor-infrayml)).

## 디스플레이 리그

- ItemDisplay 여러 개를 뼈대로 묶어 동작을 재생한다. 어종·보스·선박·상점 펫·야생 물고기 연출이 이것을 쓴다.
- 어종 → 리그 규칙: `fish/*.yml`의 `model: harbor:fish/<이름>` → `rig/fish/<이름>`.
- 거리별 생략(LOD)과 틱당 계산 예산(`rig.tick_budget_ms`·`part_budget`)이 있다. `/harbor rig stats`로 부하를 본다.

## 가구와 커스텀 블록

- **가구:** ItemDisplay(모델) + Interaction(클릭 판정) + 방벽(solid)·빛 블록(light)으로 놓는다. 보호 구역 권한을 따른다. 하우징 장식 상점(World)이 같은 정의를 `furniture:`로 참조한다.
- **커스텀 블록:** 노트블록 상태 하나에 모델 하나를 붙인다. 상태 번호는 블록끼리 겹치면 검사 오류이고, 한 번 정하면 바꾸지 않는다 (설치된 블록 모양이 바뀐다). 캐는 시간·도구·드롭을 정할 수 있다.
- 리소스팩 자동 빌드가 `note_block.json`과 블록·아이템 모델을 만든다.

## 리소스팩 파이프라인

1. **빌드 서버**(`harbor-infra.yml`의 `pack.build: true`, lobby 한 대)가 `plugins/Harbor/pack/`의 에셋을 압축한다. `pack.merge`나 `plugins/Harbor/pack-merge/`의 다른 팩도 합친다 (앞쪽 우선).
2. SHA-1이 바뀌었으면 CDN에 올린다 (API 키는 서버 설정에만 둔다).
3. Redis `harbor:pack`에 주소·SHA-1·모델/소리 목록을 저장하고 발행한다.
4. 모든 서버가 그 목록으로 쓸 수 있는 모델·소리를 판정한다. lobby는 접속자에게 그 팩을 보낸다.
5. 팩 정보가 없으면 `content/resource_pack.yml`로 판정한다.

서버 시작 때와 `/harbor pack build` 때 빌드한다. 커스텀 모델·소리는 `ItemModels`·`HarborSounds`를 거쳐서만 쓴다. 팩에 없는 모델은 바닐라 모양으로, 없는 소리는 대체음으로 나간다.

## 다른 모듈과의 연결

- **이벤트 (`core.api.event`):** `ContentReloadedEvent`, `CurrencyChangedEvent`, `PlayerFlagChangedEvent`, `ServerFlagChangedEvent`, 가구 `FurniturePlaceEvent`(취소 가능)·`FurnitureInteractEvent`(취소 가능)·`FurnitureSitEvent`(취소 가능)·`FurnitureStandEvent`·`FurnitureRemoveEvent`, 커스텀 블록 `CustomBlockPlaceEvent`·`CustomBlockBreakEvent`(취소 가능), 리그 `RigAnimationEvent`·`RigHitboxClickEvent`(요청한 리그만)

- **ExternalEvents (추가 제안):** 외부 연동 이벤트를 Redis `harbor:events`로 발행한다. 게임 모듈 이벤트는 Harbor-Web이 자동으로 옮기므로, 직접 부르는 것은 주로 다른 플러그인(Skript 등)이다.

  ```java
  Harbor.api().events().emit("skript.lottery_won", player.getUniqueId(), player.getName(),
          Map.of("prize", 1000));
  ```

  `type`은 `모듈.사건` 형식(소문자·숫자·밑줄을 점으로 잇기)이다. 아무 스레드에서 불러도 된다. Redis가 없으면 아무것도 하지 않는다.
- **테이블 소유:** `players`·`sessions`·`admin_log`·`ledger`·`pearl_ledger`는 Common, `player_season`·`fish_ledger`·`player_flags`·`server_flags`·`player_inventory`는 Core다. 나머지는 각 모듈이 갖는다. 마이그레이션 이력은 `flyway_<모듈ID>`에 남는다.

## 주의 사항

- 메인 스레드에서 DB·Redis·파일 입출력을 하면 안 된다. `dev: true`면 감지해서 경고한다.
- `inventory.sync`는 모든 서버가 같은 값이어야 한다.
- 커스텀 블록 `state`와 모든 ID는 운영 중에 바꾸지 않는다.
- 리소스팩 모델 표시와 Interaction 히트박스 크기는 실제 서버에서 아직 확인하지 않았다 (Phase 1 검증 항목).
