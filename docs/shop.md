# Harbor-Shop

유료 재화 진주와 그 사용처(코스메틱 상점, 시즌 패스, 진주 보물함), 후원 등급 구독, Tebex 결제 수신을 맡는다.

원칙('6. BM 기획' 1장):

- 진주는 골드·물비늘·어종과 교환할 수 없다.
- 유료 상품은 거래·경매할 수 없다.
- 코스메틱은 계정 영구 소유다.
- 보물함 확률을 공개한다.
- 결제로 성능이 오르지 않는다.

## 주요 기능

- **진주:** Tebex 결제로 충전한다 (10원 = 진주 1개, 보너스 없음). 잔액과 내역은 `/진주`로 본다. 진주는 Core 원장과 같은 도구로 `pearl_ledger`에 따로 기록한다.
- **코스메틱 상점 (`/상점`):** 탭은 진열(주간 로테이션) · 상시 · 시즌 한정 · 묶음이다.
  - 분류: 낚싯대 스킨 · 줄 효과 · 포획 연출 · 보트 스킨 · 펫 · 칭호 테두리 · 이름 색 · 채팅 아이콘 · 이모트 · 옷 · 어항 테마 · 하우징 장식
  - 전설 어종 기념 스킨은 그 어종을 직접 잡은 사람만 산다.
  - 묶음은 계정당 구매 한도와 판매 주차가 있다 (예: 첫 항해 패키지).
- **보관함 (`/보관함`):** 가진 코스메틱을 보고 장착한다.
  - **펫:** 플레이어를 따라다니는 리그다. 보스전 중에는 숨긴다.
  - **포획 연출:** Fishing 포획 연출의 색·모양을 바꾼다 (강도는 등급 기준).
  - **보트 스킨:** World 선박 외형.
  - **어항 테마:** Collection 개인 어항.
- **시즌 패스 「항해 일지」 (`/패스`):** 50단계, 무료·프리미엄·프리미엄+(즉시 10단계) 트랙이 있다.
  - 경험치는 포획(등급별), 일일·주간 의뢰, 해도 1%, 보스 클리어에서 얻는다.
  - 보상은 코스메틱·칭호·진주다.
- **진주 보물함 (`/보물함`):** 확률표를 공개하는 코스메틱 뽑기다. 1회·10회를 고른다.
  - 50회 안에 최상위 등급이 확정된다 (시즌 안에서 이월).
  - 중복은 진주 조각으로 바뀌고, 조각으로 교환할 수 있다.
  - 기능 스위치 `features.chest`(기본 꺼짐, 시즌 2 이후).
- **선물 (`/선물`):** 다른 플레이어에게 코스메틱을 선물한다. 하루 5회이고, 플레이 시간 5시간 이상이어야 한다 (현금 거래 우회 방지).
- **닉네임 표시 변경권 (`/닉네임`):** 진주 500, 2~12자.
- **크리에이터 코드 (`/크리에이터`):** 코드를 등록해 크리에이터를 후원한다. 기능 스위치 `features.creator_codes`(기본 꺼짐).
- **항구 축제 (`/축제`):** 서버 공동 후원 목표. 달성하면 보상 코스메틱, 참여자는 칭호를 받는다. 기능 스위치 `features.festival`(기본 꺼짐).
- **후원 등급:** 항구 후원자(`harbor`)·선단 후원자(`fleet`) 월 구독이다.
  - 월 진주(300·700), 이름 색, 채팅 아이콘, 칭호를 준다. 선단 후원자는 월 한정 코스메틱도 받는다.
  - 해지 뒤 3일 유예한다.
  - 등급은 Redis `harbor:donor`에 실어 프록시 우선 대기열과 다른 모듈이 읽는다.
- **도감 치장품:** Collection 도감 이정표를 달성하면 정해진 코스메틱을 자동으로 준다.
- **결제 수신 (Tebex 웹훅):** 한 서버(로비 권장)에서만 켠다.
  - 서명을 검증하고, 수신함에 넣은 뒤 처리한다. 실패하면 1·5·30분 뒤 재시도하고, 5번 실패하면 관리자에게 알린다.
  - 매일 새벽 최근 48시간 결제를 Tebex와 대조한다.
  - 환불·지불 거절이 오면 지급한 진주를 되돌린다. 잔액은 음수가 되지 않고, 모자란 만큼은 미납으로 남아 다음 충전에서 먼저 갚는다. 관리자는 계정을 잠글 수 있다.
  - 매일 새벽 진주 원장 합계와 잔액을 한 서버가 대조하고, 틀리면 관리자에게 알린다.

## 플레이어 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/상점` · `/상점 진열\|상시\|시즌\|묶음` | `harbor.shop` | 코스메틱 상점 (탭 바로 열기) |
| `/보관함` | `harbor.shop` | 코스메틱 보관함·장착 |
| `/패스` | `harbor.shop` | 시즌 패스 |
| `/진주` | `harbor.shop` | 진주 잔액·내역 |
| `/보물함` | `harbor.shop.chest` | 진주 보물함 (`features.chest`) |
| `/축제` | `harbor.shop` | 항구 축제 진행 |
| `/선물 <플레이어> <코스메틱>` | `harbor.shop` | 코스메틱 선물 |
| `/닉네임 <이름>` | `harbor.shop` | 닉네임 표시 변경권 사용 |
| `/크리에이터 <코드>` | `harbor.shop` | 크리에이터 코드 등록 |

## 관리자 명령

`/harbor shop …`은 `harbor.admin.shop`과 아래 하위 권한이 필요하다.

| 명령 | 권한 | 설명 |
|---|---|---|
| `/harbor shop pearl give\|take <플레이어> …` | `harbor.admin.shop.pearl` | 진주 지급·회수. `admin.pearl_confirm_over`(5,000)보다 많으면 다른 관리자 확인이 필요하다 |
| `/harbor shop pearl confirm <id>` | `harbor.admin.shop.pearl` | 다른 관리자의 대량 지급 확인 |
| `/harbor shop tx <거래>` | `harbor.admin.shop.tx` | 결제 거래 조회 |
| `/harbor shop inbox` · `inbox retry [id]` | `harbor.admin.shop.inbox` | 웹훅 수신함 보기·재처리 |
| `/harbor shop cosmetic give\|revoke <플레이어> <코스메틱>` | `harbor.admin.shop.cosmetic` | 코스메틱 지급·회수 |
| `/harbor shop flag <플레이어> lock\|unlock\|clear_debt` | `harbor.admin.shop.flag` | 계정 잠금·해제, 미납 지우기 |
| `/harbor shop chest stats <보물함>` | `harbor.admin.shop.chest` | 보물함 통계 |
| `/harbor shop reconcile` · `reconcile audit` | `harbor.admin.shop.reconcile` | Tebex 결제 대조 즉시 실행, 진주 정합성 검사 즉시 실행 |
| `/harbor shop pass exp <플레이어> <양>` | `harbor.admin.shop.pass` | 패스 경험치 지급 |
| `/harbor shop donor reconcile` · `donor show <플레이어>` | `harbor.admin.shop.donor` | 후원 등급 대조, 보기 |

## 콘텐츠 파일

| 파일 | 내용 | 주요 필드 |
|---|---|---|
| `cosmetics.yml` | 코스메틱 | `id` `name` `category` `availability`(permanent·rotation·season·pass·chest·donor·free) `grade` `price` `model` |
| `shop/products.yml` | Tebex 패키지 ↔ 지급 | `tebex_package_id`(겹치면 오류) `type`(pearls·pearls_custom·subscription·festival) `pearls` `tier` `price_krw` |
| `shop/rotation.yml` | 주간 로테이션 | `repeat` `weeks`(`week` `items`) |
| `shop/bundles.yml` | 묶음 | `price` `items` `account_limit` `weeks` |
| `shop/pass_s1.yml` | 시즌 패스 | `season`(시즌당 하나) `levels` `exp_per_level` `prices` `premium_plus_levels` `exp_sources` `rewards`(`level` `track` `cosmetic`·`pearls`·`title`) |
| `shop/chests.yml` | 보물함 확률표 | `price` `table_version` `pity` `shards` `exchange` `entries`(chest 전용 코스메틱, 등급 일치, 합계 100%) |
| `shop/catch_fx.yml` | 포획 연출 프리셋 | `pillar_particle` `pillar_colors` `firework` `firework_colors` `splash_particle` `sound` |
| `shop/festivals.yml` | 항구 축제 | `season` `goal_krw` `rewards` `contributor_title` |
| `shop/creators.yml` | 크리에이터 코드 | `code`(겹치면 오류) `name` `share_rate` `supporter_title` |
| `shop/shop.yml` | 수치·기능 스위치 | 아래 표 |

보물함 확률표를 바꾸면 `table_version`을 올리고 바꾸기 전에 공지한다. 게임 안 확률표는 이 파일에서 그대로 만든다.

## 설정 값

### `content/shop/shop.yml`

| 키 | 내용 |
|---|---|
| `features` | `chest` `festival` `creator_codes` `gifting` `name_change` |
| `topup` | 결제 통화, 최소·단위 금액 |
| `admin.pearl_confirm_over` | 이보다 많은 관리자 지급은 다른 관리자 확인 |
| `gift` | `daily_limit` `min_playtime_hours` |
| `name_change` | `price` `min_length` `max_length` |
| `donor` | `grace_days`, 등급별 `monthly_pearls` `name_color` `chat_icon` `title`, `monthly_cosmetics` |
| `inbox` | `retry_minutes` `max_attempts` `poll_seconds` |
| `reconcile` | `hour` `window_hours` |
| `pet` | `view_distance` `follow_distance` `update_ticks` `max_parts` |
| `catch_fx` | 등급별 빛기둥 높이·폭죽 여부 |
| `collection_rewards` | 도감 이정표 키 → 코스메틱 |

### `plugins/Harbor-Shop/config.yml` (서버별, 비밀키 포함이라 커밋하지 않음)

| 키 | 내용 |
|---|---|
| `webhook.enabled` · `bind` · `port` · `path` | Tebex 웹훅 수신기. 내부 주소에만 묶고 앞단 리버스 프록시가 TLS를 맡는다 |
| `webhook.signature` · `secret` · `allowed_ips` | 서명 형식(`tebex_v1`·`hmac_body`), 비밀 키, 허용 IP |
| `webhook.process_inbox` | 이 서버가 수신함 처리기도 돌릴지 |
| `tebex.secret` · `payments_url` | 일일 결제 대조용 API |

## 다른 모듈과의 연결

- **제공 서비스:** `PearlService`, `CosmeticService`, `SeasonPassService`(`registerSource`로 경험치 출처 등록), `DonorService`
- **이벤트:** `PearlBalanceChangedEvent`, `CosmeticGrantedEvent`, `CosmeticEquipEvent`, `SeasonPassLevelUpEvent`, `DonorTierChangedEvent`, `FestivalProgressEvent`
- **쓰는 것:** Fishing(포획 연출 덮어쓰기, 패스 경험치), Collection(도감 이정표 → 코스메틱, 어항 테마 보유 확인 제공), Season(칭호, 주차, 패스 정리 훅)
- **선택 의존:** Quest·World·Boss는 `SeasonPassService.registerSource`로 직접 연결한다. World 선박 스킨(`ShipSkinProvider`), Boss 보스전 중 펫 숨김. 일부 이벤트는 클래스 이름으로 반사 구독한다.
- **원장 사유 (진주):** `TOPUP` `SUBSCRIPTION_MONTHLY` `DONOR_MONTHLY` `PASS_REWARD` `PASS_PREMIUM` `PASS_LEVEL_BUY` `CHEST_OPEN` `NAME_CHANGE` `GIFT` `REFUND_REVERSAL` `CHARGEBACK_REVERSAL` `CHARGEBACK_RESTORE`

## 주의 사항

- Tebex 서명 형식은 법률·기술 검토 항목이다. Phase 3 착수 때 Tebex 최신 문서로 다시 확인한다. 결제 목록 API 주소도 마찬가지다.
- `products.yml`의 `tebex_package_id`는 예시 번호다. 실제 상점을 만든 뒤 바꾼다.
- `paper-plugin.yml`에 `harbor.admin.shop.festival` 권한이 있지만, 대응하는 `/harbor shop festival` 하위 명령은 지금 코드에 없다. `flag`의 빚 지우기 하위 명령 이름은 `clear_debt`다 (`paper-plugin.yml` 설명의 `debt clear`와 다름).
- 가격·확률·한도 중 문서에 없는 값은 제안값이다.
