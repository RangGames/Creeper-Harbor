# Harbor-Web REST API 명세

웹 프런트엔드·백엔드 개발자용 문서다. 기계가 읽는 명세는 [openapi.yaml](openapi.yaml)(OpenAPI 3.1), 이벤트·웹훅은 [events.md](events.md)에 있다. 모두 코드를 기준으로 썼고, 코드와 다르면 코드가 맞다 (코드 위치는 [Source map](#source-map)).

## 1. 개요

- **무엇:** 크리퍼 항구 게임 서버(Purpur) 안에서 도는 Harbor-Web 플러그인이 여는 **읽기 전용** HTTP API다. 쓰기(지급, 경매 등록 등)는 없다. 모든 경로는 `GET`이다.
- **어디서:** `config.yml http.enabled: true`인 서버에서만 열린다. 운영 의도는 한 서버(예: lobby)에서만 켜는 것이다. 여러 서버에서 켜도 동작은 하지만 서로 독립이다 (특히 `/v1/events`의 seq·커서는 서버마다 따로이고, 응답의 `server`로 어느 서버의 seq인지 알 수 있다). REST에는 리더 잠금이 없다 (잠금은 웹훅에만 있다).
- **주소:** `http://<http.bind>:<http.port>/v1/…`. 기본 `127.0.0.1:8765`. HTTPS는 지원하지 않으므로 앞에 리버스 프록시(nginx, Caddy 등)를 두고 TLS를 끝낸다. 밖에서 직접 받으려면 `bind: 0.0.0.0`.
- **구현:** JDK 내장 `HttpServer` + 요청마다 가상 스레드. DB와 모듈 캐시를 읽는 동안 게임 메인 스레드를 막지 않는다. 메인 스레드에서만 읽을 수 있는 값은 메인 스레드에 맡기고 **최대 5초** 기다린다 (넘으면 503).
- **데이터 신선도:**
  - 플레이어 자료(요약·도감·퀘스트·원장)는 DB에서 읽는다. 도감·퀘스트 진행, 레벨·경험치·플레이 시간은 게임 서버가 지연 쓰기(write-behind, 키별 마지막 값을 주기적으로 씀)하므로 **접속 중인 플레이어는 몇 분 늦을 수 있다.** 재화(골드·물비늘 잔액과 원장)는 즉시 트랜잭션이라 늦지 않다.
  - 시세·기록·랭킹은 그 모듈의 메모리 캐시를 읽는다 (랭킹은 기본 10분 주기 집계). 경매 목록은 DB를 직접 읽는다 (캐시 없음).

### 경로 묶음과 필요한 모듈

모듈 경로는 **Harbor-Web이 켜지는 시점에** 그 모듈 플러그인이 켜져 있을 때만 등록된다. 등록되지 않은 경로는 `404 not_found`다. 실제 등록된 목록은 `GET /v1`로 확인한다.

| 묶음 | 경로 | 필요한 플러그인 |
|---|---|---|
| 메타 | `/v1/health`, `/v1` | Harbor-Core |
| 코어 | `/v1/server`, `/v1/players/{id}`, `/v1/players/{id}/ledger`, `/v1/events`, `/v1/content/fish` | Harbor-Core |
| 도감 | `/v1/players/{id}/collection`, `/v1/records` | Harbor-Collection |
| 퀘스트 | `/v1/players/{id}/quests` | Harbor-Quest |
| 경제 | `/v1/market`, `/v1/auctions` | Harbor-Economy |
| 시즌 | `/v1/rankings`, `/v1/rankings/{board}` | Harbor-Season |

경로가 등록됐더라도 모듈 서비스를 찾지 못하면 `503 unavailable`이다.

## 2. 설정 (`plugins/Harbor-Web/config.yml`)

```yaml
http:
  enabled: false
  bind: 127.0.0.1
  port: 8765
  rate_limit_per_minute: 120   # 토큰마다. 0 이하면 무제한
  recent_events: 500           # /v1/events 버퍼 크기 (최소 10)
  cors_origins: []             # 예: [https://creeper.wiki]
  tokens:
    - name: website
      token: 긴-임의-문자열
```

이 파일에는 비밀이 들어가므로 저장소에 넣지 않는다. 설정은 플러그인이 켜질 때 한 번 읽는다 (바꾸면 서버 재시작).

## 3. 인증

```
Authorization: Bearer <token>
```

- 헤더 이름은 `Authorization`(HTTP 규칙상 대소문자 무관), 값은 정확히 `Bearer `(B 대문자, 공백 하나)로 시작해야 한다. `bearer xxx`는 401이다.
- 토큰은 앞뒤 공백을 지운 뒤 `http.tokens[].token`과 고정 시간 비교한다. 빈 토큰은 무엇과도 맞지 않는다.
- 토큰이 하나도 설정되지 않으면 `/v1/health`를 뺀 모든 요청이 401이다 (시작 로그에 경고).
- `/v1/health`와 `OPTIONS`(CORS preflight)는 인증하지 않는다.
- 토큰의 `name`은 속도 제한 키이자 식별용이다. 같은 이름을 여러 토큰에 쓰면 한도를 같이 쓴다.
- **토큰을 브라우저에 넣지 않는다.** 이 API는 서버 대 서버(웹 백엔드 → Harbor-Web)용이다. 브라우저에서 직접 부르게 하면 토큰이 노출된다. 꼭 필요하면 읽기 전용임을 감안해 전용 토큰과 낮은 한도를 둔다.

### CORS

요청의 `Origin`이 `http.cors_origins`에 **정확히** 있을 때만 응답에 다음을 붙인다.

```
Access-Control-Allow-Origin: <그 Origin>
Vary: Origin
Access-Control-Allow-Headers: Authorization, Content-Type
Access-Control-Allow-Methods: GET, OPTIONS
```

`OPTIONS`는 어느 경로든 본문 없이 204다. `Access-Control-Max-Age`·`Allow-Credentials`는 보내지 않는다.

## 4. 처리 순서와 속도 제한

요청 하나는 이 순서로 처리된다 (`ApiServer.handle`):

1. `OPTIONS` → 204
2. 경로가 `/v1/health`(끝 `/` 허용) → 200 (인증·속도 제한·메서드 검사 없음)
3. 토큰 없음/틀림 → 401 `unauthorized`
4. 그 토큰의 이번 분 요청 수가 한도 초과 → 429 `rate_limited`
5. `GET`이 아님 (`HEAD` 포함) → 405 `method_not_allowed`
6. 경로가 `/v1` 또는 `/v1/` → 경로 목록
7. 경로·쿼리 퍼센트 인코딩이 깨짐 → 400 `bad_request`
8. 맞는 경로 없음 → 404 `not_found`
9. 처리기 실행 → 200 또는 처리기의 오류

**속도 제한** (`RateLimiter`):
- 토큰 `name`별 **고정 창**이다. 창은 유닉스 시각 기준 매 분(0초~59초)이고 분이 바뀌면 0부터 다시 센다. 그래서 분 경계 앞뒤로 최대 2배까지 몰릴 수 있다.
- 429로 거절된 요청도 횟수에 들어간다.
- `Retry-After`·`X-RateLimit-*` 헤더는 없다. 429를 받으면 다음 분까지 기다린다.
- 인증 실패(401) 요청은 세지 않는다. 서버 메모리에만 있어 재시작하면 초기화된다.

## 5. 응답·오류 형식

모든 응답(204 제외)은 `Content-Type: application/json; charset=utf-8`이다.

```json
{"ok": true, "data": <경로마다 다름>}
{"ok": false, "error": {"code": "player_not_found", "message": "플레이어를 찾지 못했습니다: Steve"}}
{"ok": false, "error": {"code": "bad_parameter", "message": "league는 tier1, tier2, all 중 하나여야 합니다: tier9", "param": "league", "allowed": ["tier1", "tier2", "all"]}}
```

- 분기는 `error.code`로 한다. `message`는 사람이 읽는 한국어이고 바뀔 수 있다.
- `bad_parameter`에는 `error.param`(파라미터 이름)이 있고, 선택형 파라미터면 `error.allowed`(허용 값 배열)도 있다.
- **null 값은 키째 빠진다** (Gson 기본). 선택 필드는 "없으면 키가 없다"로 다룬다.
- 캐시 헤더(`Cache-Control`, `ETag`, `Last-Modified`)는 없다. 필요하면 앞단 프록시나 웹 백엔드에서 캐시한다.
- 500·503의 원인(SQL 오류 등)은 서버 로그에만 남고 응답에는 들어가지 않는다.

| HTTP | code | 언제 |
|---|---|---|
| 400 | `bad_request` | 경로·쿼리의 퍼센트 인코딩이 잘못됨 (`%zz`) |
| 400 | `bad_id` | `{id}`가 UUID도 마인크래프트 이름(`[A-Za-z0-9_]{1,16}`)도 아님 |
| 400 | `bad_parameter` | 정수 파라미터(`limit`·`after`·`cursor`)가 10진 정수가 아님, 또는 선택형 파라미터(`currency`·`scope`·`category`·`league`)가 허용 값이 아님 |
| 401 | `unauthorized` | 토큰 없음·틀림 |
| 404 | `not_found` | 등록되지 않은 경로 (모듈이 꺼진 경우 포함) |
| 404 | `player_not_found` | players 행이 없음 (UUID로 불러도 이름으로 불러도, 모든 `/players/{id}…` 경로) |
| 404 | `fish_not_found` | records의 `fish`가 없거나 형식이 틀리거나 콘텐츠에 없음 |
| 404 | `board_not_found` | 없는 랭킹 보드 |
| 405 | `method_not_allowed` | GET이 아님 |
| 429 | `rate_limited` | 분당 한도 초과 |
| 500 | `internal` | 처리 중 예외 |
| 503 | `timeout` | DB 또는 메인 스레드 읽기가 5초를 넘김 |
| 503 | `interrupted` | 처리 스레드 중단 (서버 종료 중 등) |
| 503 | `unavailable` | 모듈 서비스(PriceService·AuctionService·RecordService·RankingService)가 없음 |

**쿼리 파라미터 규칙** (`Router.Request`):
- 빈 값(`?limit=`)은 없는 것과 같다 (기본값).
- 정수 파라미터가 10진 정수가 아니면 400 `bad_parameter`다. 정수이면 최소·최대로 잘라 쓴다 (`limit=0` → 1, `limit=9999` → 최대).
- 선택형 파라미터는 허용 값(소문자, 정확히 일치)이 아니면 400 `bad_parameter`이고 `error.allowed`에 허용 값이 온다. 모든 경로가 같은 규칙이다.
- 같은 이름이 여러 번 오면 마지막 값을 쓴다.
- 경로 끝 `/`와 겹친 `/`는 무시한다 (`/v1//server/` = `/v1/server`).

## 6. 표기 규칙

| 항목 | 규칙 |
|---|---|
| Harbor ID | `<namespace>:<path>`, 보통 `harbor:<종류>/<이름>` (소문자·숫자·밑줄). 예 `harbor:fish/harbor_mackerel`, `harbor:quest/main/ch1_first_cast`, `harbor:region/harbor`. **ID는 바뀌지 않는다** (이름은 바뀔 수 있음). 저장·조인 키로 ID를 쓴다 |
| 플레이어 | UUID 문자열 (하이픈 포함 소문자). 이름은 바뀔 수 있으므로 키로 쓰지 않는다 |
| 시각 | ISO-8601 UTC, `Z`로 끝남 (Java `Instant.toString`). 소수 초가 없거나(`…:00Z`) 있거나(`….123Z`) 하므로 ISO 파서로 읽는다. 게임 내 "날"(일일 초기화 06:00)은 이 API에서 다루지 않는다 |
| 골드 (`gold`) | 정수 (long). 소수 없음 |
| 물비늘 (`mulbineul`) | 정수 (long). 시즌 재화. 요약의 값은 `mulbineul_earned − mulbineul_spent` |
| 크기 | cm, 소수 첫째 자리 (`41.2`) |
| 품질 | 별 개수 1~5 정수 |
| 등급 (`grade`) | `common`·`uncommon`·`rare`·`epic`·`legendary`·`mythic` |
| 리그 | 쿼리와 응답 모두 소문자 `tier1`·`tier2`·`all` |
| 필드 이름 | **모든 REST 응답과 웹훅 `json` 본문은 snake_case**다 (모듈 레코드도 명시적으로 옮겨 쓴다). Redis를 직접 구독할 때의 원본 메시지만 Common 레코드 그대로 camelCase다 ([events.md](events.md#2-redis-메시지-형식)) |
| enum 성격의 값 | 소문자 키 (`currency: "gold"`, `league: "all"`, `reason: "fish_sell"`, `state: "completed"`). 쿼리 파라미터가 받는 값과 같다 |
| 목록 | 페이지가 있는 목록(`/v1/events`, `/v1/auctions`)은 `{items, next, …}` 객체, 페이지가 없는 목록은 배열 |
| 시즌 | 정수 시즌 번호. "이번 시즌"은 REST 서버의 `harbor-infra.yml` 시즌 값 |

## 7. 경로 목록

| 메서드·경로 | 내용 | 필요 모듈 | openapi operationId |
|---|---|---|---|
| `GET /v1/health` | 생존 확인 (토큰 불필요) | – | `getHealth` |
| `GET /v1` | 등록된 경로 목록 | – | `listRoutes` |
| `GET /v1/server` | 서버 ID·시즌·접속 인원 | – | `getServer` |
| `GET /v1/players/{id}` | 플레이어 요약 | – | `getPlayer` |
| `GET /v1/players/{id}/ledger` | 재화 원장 최근 기록 | – | `getPlayerLedger` |
| `GET /v1/players/{id}/collection` | 플레이어 도감 | Collection | `getPlayerCollection` |
| `GET /v1/players/{id}/quests` | 플레이어 퀘스트 | Quest | `getPlayerQuests` |
| `GET /v1/events` | 최근 외부 이벤트 (커서) | – | `getRecentEvents` |
| `GET /v1/content/fish` | 어종 목록 | – | `listFish` |
| `GET /v1/market` | 어종 시세 계수 | Economy | `getMarket` |
| `GET /v1/auctions` | 진행 중인 경매 | Economy | `listAuctions` |
| `GET /v1/records` | 어종별 서버 최대어 기록 | Collection | `getRecords` |
| `GET /v1/rankings` | 랭킹 보드 목록 | Season | `listRankingBoards` |
| `GET /v1/rankings/{board}` | 랭킹 순위표 | Season | `getRankingBoard` |

총 14개. 아래 예시의 `$HARBOR`는 `https://harbor-api.example.com` 같은 앞단 주소, `$TOKEN`은 토큰이다.

## 8. 경로별 설명

### GET /v1/health

```bash
curl -s $HARBOR/v1/health
# {"ok":true,"data":{"status":"up"}}
```

로드밸런서·모니터링용. HTTP 서버가 살아 있다는 뜻일 뿐 DB·Redis 상태는 확인하지 않는다. 구현상 메서드를 검사하지 않는다.

### GET /v1

```bash
curl -s -H "Authorization: Bearer $TOKEN" $HARBOR/v1
```

`[{path, description}]`. 이 서버에 실제 등록된 경로만 나온다 (`/v1`·`/v1/health` 제외). 모듈 구성이 서버마다 다를 수 있으므로 시작할 때 이 목록으로 기능을 켜고 끄면 편하다.

### GET /v1/server

```bash
curl -s -H "Authorization: Bearer $TOKEN" $HARBOR/v1/server
# {"ok":true,"data":{"server":"lobby-1","season":1,"online":57,"online_scope":"network","servers_reporting":3,"server_online":12}}
```

- `online`: **네트워크 전체** 접속 인원. 모든 백엔드가 5초마다 Redis `harbor:server`로 보내는 하트비트 중 15초 안에 받은 서버별 마지막 값을 더한다 (Economy 시세 계산과 같은 방식).
- `online_scope`: `network`이면 위 합계, `server`이면 받은 하트비트가 없어서(Redis 장애, REST 서버가 막 켜짐) 이 서버 인원으로 대신한 값이다.
- `servers_reporting`: 합계에 들어간 서버 수 (`server`이면 0).
- `server_online`: REST를 연 이 서버 한 대의 인원.

### GET /v1/players/{id}

```bash
curl -s -H "Authorization: Bearer $TOKEN" $HARBOR/v1/players/Steve
curl -s -H "Authorization: Bearer $TOKEN" $HARBOR/v1/players/069a79f4-44e9-4726-a5be-fca90e38aaf5
```

```json
{"ok":true,"data":{
  "uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Steve",
  "first_join":"2026-10-01T09:12:44.120Z","last_join":"2026-11-20T11:58:03Z","playtime_seconds":183920,
  "season":{"season":1,"job_line":"collect","job_tier":2,"level":34,"exp":1820,
            "gold":125400,"mulbineul":37,"reputation":410}}}
```

- `{id}` 해석 (모든 `/players/{id}…` 공통): UUID 형식이면 UUID로, 아니면 `[A-Za-z0-9_]{1,16}`인 이름으로 보고 players 테이블에서 `last_join`이 가장 최근인 행을 찾는다. 이름 비교의 대소문자 구분은 DB 콜레이션을 따른다. 형식이 둘 다 아니면 400 `bad_id`, UUID든 이름이든 players 행이 없으면 404 `player_not_found`.
- `season`: 이번 시즌 행이 없으면(이번 시즌에 접속한 적 없음) 키가 없다. `job_line`은 2차 전직 전이면 키가 없다 (`explore`·`collect`·`produce`·`economy`). `job_tier`는 1~3.
- `playtime_seconds`는 누적 초. 접속 중이면 마지막 저장 시점까지다.

### GET /v1/players/{id}/ledger

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$HARBOR/v1/players/Steve/ledger?currency=mulbineul&limit=20"
```

| 파라미터 | 기본 | 범위 |
|---|---|---|
| `currency` | `gold` | `gold`·`mulbineul` (그 밖은 400 `bad_parameter`) |
| `limit` | 50 | 1~200 |

```json
{"ok":true,"data":[
  {"id":99812,"currency":"gold","delta":1520,"balance_after":125400,"reason":"fish_sell","ref_id":"sell:4f1c2a","created_at":"2026-11-20T11:57:41.302Z"}]}
```

- 원장 id 내림차순(최신순). 시즌 구분 없이 전 기간이다. 페이지 없음, 최대 200행 (배열).
- `currency`·`reason`은 소문자 키다. `reason`은 `LedgerReason` 이름의 소문자이고 이벤트 `economy.currency_changed`의 `reason`과 같은 값이다. 예전 사유가 남아 있을 수 있으니 모르는 값도 처리한다. `ref_id`는 없으면 키가 없다.
- 진주(PEARL)는 이 경로로 볼 수 없다.
- players 행이 없는 플레이어는 UUID로 불러도 404 `player_not_found`.

### GET /v1/players/{id}/collection

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$HARBOR/v1/players/Steve/collection?scope=season"
```

| 파라미터 | 기본 | 값 |
|---|---|---|
| `scope` | `total` | `total`(누적)·`season`(이번 시즌). 그 밖은 400 `bad_parameter` |

```json
{"ok":true,"data":{"scope":"total","registered":2,"total":120,"percent":1.7,"entries":[
  {"fish":"harbor:fish/harbor_mackerel","first_caught":"2026-10-01T09:30:00Z","max_size":41.2,"best_quality":4,"catch_count":57,"fish_name":"항구 고등어"}]}}
```

- `total`은 현재 콘텐츠의 어종 수, `percent`는 소수 첫째 자리 (`total`이 0이면 `0`).
- `entries` 순서는 정해지지 않았다. 화면에서는 `GET /v1/content/fish`의 `no`로 정렬한다.
- `fish_name`은 콘텐츠에서 빠진 어종이면 없다.
- `scope=season`이면 읽은 시즌 번호 `season`이 함께 온다.
- 지연 쓰기 때문에 접속 중인 플레이어는 몇 분 늦을 수 있다. players 행이 없는 플레이어는 UUID로 불러도 404.

### GET /v1/players/{id}/quests

```bash
curl -s -H "Authorization: Bearer $TOKEN" $HARBOR/v1/players/Steve/quests
```

```json
{"ok":true,"data":{"season":2,"quests":[
  {"quest":"harbor:quest/main/ch1_first_cast","main":true,"season":1,"state":"completed","updated_at":"2026-10-01T10:02:11Z","quest_name":"첫 캐스팅"},
  {"quest":"harbor:quest/daily/bring_mackerel","main":false,"season":2,"state":"active","updated_at":"2026-11-20T09:15:40Z","quest_name":"고등어 납품"}]}}
```

- 이번 시즌의 퀘스트 행 + 이전 시즌에서 이월된 메인 퀘스트(`harbor:quest/main/…`, `main: true`) 행. **퀘스트마다 한 줄**이다. 같은 메인 퀘스트가 여러 시즌에 행이 있으면 게임이 쓰는 것과 같이 가장 최근 시즌의 행만 준다 (`season`이 그 행의 시즌). 위쪽 `season`은 이번 시즌 번호다.
- `state`: `active`·`ready`(목표 달성, 제출 가능)·`completed`·`failed`(기간 종료). 포기한 퀘스트는 행이 지워져 없다. 아직 받지 않은 퀘스트(잠김·수락 가능)도 나오지 않는다.
- 이름 키는 `quest_name`이다 (이벤트와 같다). 진행도(목표별 수치)는 주지 않는다.
- quest ID 오름차순. players 행이 없는 플레이어는 UUID로 불러도 404.

### GET /v1/events

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$HARBOR/v1/events?after=1040&type=collection.*&limit=50"
```

| 파라미터 | 기본 | 범위 |
|---|---|---|
| `after` | 0 | 이 seq보다 큰 것만 (보통 앞 응답의 `next`). 음수는 0 |
| `type` | (전부) | type glob, `*`만 와일드카드 |
| `limit` | 100 | 1~500 |

```json
{"ok":true,"data":{"server":"lobby-1","last":1042,"next":1042,"items":[
  {"seq":1042,"event":{"id":"a8d2…","type":"collection.record_broken","version":1,"server":"main-1",
   "occurred_at":"2026-11-20T12:00:05.901Z","player":"069a…","player_name":"Steve","data":{"fish":"harbor:fish/harbor_mackerel","size":41.2,"…":"…"}}}]}}
```

| 필드 | 뜻 |
|---|---|
| `server` | seq를 붙인 REST 서버 ID. **seq와 커서는 이 서버에서만 뜻이 있다** |
| `last` | 이 서버가 지금까지 붙인 마지막 seq (필터와 무관) |
| `next` | 다음 요청의 `after`. 페이지가 꽉 찼으면 마지막 item의 seq, 아니면 `last`, 보낸 `after`가 `last`보다 크면 0 |
| `items[].event` | snake_case 이벤트 ([events.md](events.md)). `event.server`는 이벤트가 난 게임 서버 |

폴링 방법:
1. 처음에는 `after=0`(버퍼 전체)으로 읽는다.
2. 응답의 `next`와 `server`를 저장하고 다음 요청의 `after`로 `next`를 쓴다. `type` 필터를 써도 `next`를 그대로 쓰면 된다.
3. `next`가 0으로 오면 REST 서버가 재시작된 것이다 (seq가 1부터 다시). 그대로 `after=0`으로 읽고, 이미 처리한 것은 `event.id`로 거른다.
4. 응답의 `server`가 저장한 값과 다르면 다른 REST 서버가 답한 것이다 (로드밸런서 뒤에 여러 대). 저장한 커서를 버리고 `after=0`으로 읽은 뒤 `event.id`로 거른다. 서버 간 공통 순서는 만들지 않는다. REST는 한 서버에서만 켜거나 고정 라우팅을 권한다.

주의:
- 버퍼는 REST 서버 메모리에 최근 `recent_events`개(기본 500)만 있다. 폴링 간격 동안 그보다 많이 쌓이면 **빠진 이벤트를 알려 주지 않고** 잃는다. `fishing.caught`·`economy.currency_changed`가 많으면 버퍼가 빨리 돈다.
- seq는 REST 서버가 받은 순서다. 서버 간 실제 발생 순서와 다를 수 있다 (`occurred_at` 참고).
- `event.data`의 정수는 정수(`13`)로, 소수가 있는 값은 실수로 나온다. 이벤트별 data 필드는 [events.md](events.md#3-이벤트-카탈로그).

### GET /v1/content/fish

```bash
curl -s -H "Authorization: Bearer $TOKEN" $HARBOR/v1/content/fish
# {"ok":true,"data":[{"id":"harbor:fish/harbor_mackerel","no":1,"name":"항구 고등어","grade":"common","region":"harbor:region/harbor"}, …]}
```

도감 번호(`no`) 오름차순. 현재 콘텐츠 스냅샷이라 서버가 콘텐츠를 리로드하면 바뀐다 (이벤트 `server.content_reloaded`). 웹에서 길게 캐시해도 된다.

### GET /v1/market

```bash
curl -s -H "Authorization: Bearer $TOKEN" $HARBOR/v1/market
# {"ok":true,"data":[{"fish":"harbor:fish/harbor_mackerel","name":"항구 고등어","coefficient":0.874,"next":0.902}, …]}
```

- 모든 어종을 `no` 순으로. `coefficient`는 현재 시세 계수(1.0 = 기준가), `next`는 다음 시간 예측(아직 없으면 키 없음). 소수 셋째 자리.
- 실제 판매가는 기준가 × 품질 배율 × 계수 × 개인 보정 × 지역 계수라 이 값만으로 정확한 가격을 낼 수 없다. 시세 갱신은 이벤트 `economy.market_updated`로 알 수 있다.

### GET /v1/auctions

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$HARBOR/v1/auctions?category=fish&limit=20"
```

| 파라미터 | 기본 | 값 |
|---|---|---|
| `category` | `all` | `all`·`fish`·`gear`·`bait`·`material`·`food`·`other` (그 밖은 400 `bad_parameter`) |
| `limit` | 50 | 1~200 |
| `cursor` | (첫 페이지) | 앞 응답의 `next` |

```json
{"ok":true,"data":{"next":"5101","items":[{"id":5120,"seller":"069a…","seller_name":"Steve","item_id":"harbor:fish/silver_anchovy","item_name":"은빛 멸치",
  "category":"fish","amount":1,"price":4200,"created_at":"2026-11-20T11:00:00Z","expires_at":"2026-11-22T11:00:00Z"}]}}
```

- 만료되지 않은 진행 중 목록, 최신순(listing id 내림차순). `price`는 묶음 전체 골드.
- **페이지:** `next`가 있으면 그 값을 다음 요청의 `cursor`로 넣는다. `next`가 없으면 마지막 페이지다. 키셋 방식이라 페이지 사이에 새 등록이 생겨도 겹치거나 빠지지 않는다 (그 사이 팔리거나 만료된 경매는 빠진다). `cursor`는 불투명한 문자열로 다룬다.
- Economy의 `auction_listing` 테이블을 직접 읽는다 (읽기 전용, 캐시 없음). 게임 경매 화면의 45개 페이지와 무관하다.

### GET /v1/records

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$HARBOR/v1/records?fish=harbor:fish/harbor_mackerel&league=all&limit=5"
# {"ok":true,"data":[{"rank":1,"player":"069a…","name":"Steve","size":41.2,"caught_at":"2026-11-20T12:00:05.901Z"}]}
```

| 파라미터 | 기본 | 값 |
|---|---|---|
| `fish` | (필수) | `harbor:fish/<이름>` 또는 `fish/<이름>`. 없거나 틀리면 404 `fish_not_found` |
| `league` | `all` | `tier1`·`tier2`·`all` (그 밖은 400 `bad_parameter`) |
| `limit` | 10 | 1~10 |

이번 시즌의 그 어종 최대어 상위(크기 내림차순, `rank`는 1부터). 기록이 없으면 빈 배열. 리그는 기록 당시 직업 단계로 정해진다.

### GET /v1/rankings

```bash
curl -s -H "Authorization: Bearer $TOKEN" $HARBOR/v1/rankings
# {"ok":true,"data":[{"id":"level","name":"<gold>시즌 레벨","leagues":["all","tier1","tier2"],"size":100}, …]}
```

`name`은 MiniMessage 원문이다 (`<gold>` 같은 태그를 지우거나 해석해서 쓴다). `leagues`의 첫째가 기본 리그.

### GET /v1/rankings/{board}

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$HARBOR/v1/rankings/level?league=tier1"
```

```json
{"ok":true,"data":{"board":"level","league":"tier1","season":1,"computed_at":"2026-11-20T12:00:00.004Z","entries":[
  {"rank":1,"uuid":"069a…","name":"Steve","value":52.0,"secondary":18200.0,"achieved_at":"2026-11-19T22:41:10Z","members":1}]}}
```

- 집계 캐시(보드별 `refresh`, 기본 10분)를 읽는다. `computed_at`으로 집계 시각을 보여 줄 수 있다.
- 아직 집계 전이거나 그 보드가 이 리그를 집계하지 않으면 `entries: []`, `computed_at: "1970-01-01T00:00:00Z"`.
- `league`는 쿼리와 같은 소문자 키. `league`가 허용 값이 아니면 400 `bad_parameter`. `value`·`secondary`는 항상 실수 표기(`52.0`)다. `value` 단위는 보드의 source에 따른다 (최대어 cm, 레벨, 도감 수, 보스 처치 시간 ms 등). `uuid`는 없으면 키가 없다. 동점은 먼저 달성한 사람이 위다.

## 9. 웹 백엔드 구현 권장

- Harbor-Web은 게임 서버 안에서 돈다. 웹 트래픽을 그대로 넘기지 말고 웹 백엔드에서 캐시한다 (`content/fish` 몇 시간, `market`·`rankings` 수 분, 플레이어 자료 30초~수 분).
- 실시간 알림은 폴링보다 웹훅(`json`) 또는 Redis 구독을 쓴다 ([events.md](events.md)).
- 오류는 `error.code`로 분기하고, 503은 잠시 뒤 재시도, 429는 다음 분까지 대기한다.

## Open questions

서버 팀이 알고 있지만 그대로 두는 동작과 제약이다.

1. **이름으로 찾을 때의 대소문자 구분**은 DB 콜레이션에 달려 있다 (코드는 `name = ?` 비교만 한다).
2. **도감 테이블 이름이 아직 `codex_total`/`codex_season`이다** (모듈 이름은 collection으로 바뀜). API 응답에는 영향이 없다. 랭킹 보드 ID에도 `codex`가 남아 있다 (`content/season/rankings.yml`). 보드 ID는 콘텐츠 값이라 바뀌면 `/v1/rankings/{board}` 경로가 바뀐다.
3. **Redis 원본 메시지는 camelCase다.** `harbor:events`를 직접 구독하면 Common `ExternalEvent` 레코드 그대로(`occurredAt`, `playerName`)다. REST와 웹훅만 snake_case로 옮긴다. 원본을 바꾸려면 Common·Core 변경이 필요하다 ([events.md](events.md#2-redis-메시지-형식)).
4. **Redis pub/sub 특성:** 구독하지 않던 동안의 이벤트, 리더 교체 중 웹훅, 버퍼에서 밀려난 `/v1/events` 이벤트는 잃는다 ([events.md](events.md)).
5. **버전 정책:** 경로 접두사 `/v1` 말고 응답 형식 버전 표시는 없다. 1.0 전에는 아래 Changelog처럼 형식이 바뀔 수 있다. 응답 필드 추가는 예고 없이 될 수 있다고 가정하고, 모르는 필드는 무시하게 만든다.

## Changelog (pre-1.0)

외부 소비자가 생기기 전에 정리한 변경이다 (하위 호환 없음).

- **필드 이름 snake_case 통일:** ledger(`balance_after`, `ref_id`, `created_at`), auctions(`seller_name`, `item_id`, `item_name`, `expires_at`, 새 `created_at`), records(`caught_at`, 새 `rank`), rankings/{board}(`computed_at`, `achieved_at`), `/v1/events`와 웹훅 `json`의 이벤트(`occurred_at`, `player_name`). quests의 이름 키는 `name` → `quest_name`.
- **enum 값 소문자 통일:** ledger `currency`(`gold`)·`reason`(`fish_sell`), rankings `league`(`all`).
- **이벤트 정수 보존:** `/v1/events`와 웹훅 `json`에서 정수가 `13.0`이 아니라 `13`으로 나온다. 웹훅 `json` 본문은 HTML 문자를 이스케이프하지 않는다.
- **`/v1/auctions` 페이지:** `limit` 최대 200을 실제로 지키고, 응답이 `{items, next}`이며 `cursor`로 다음 페이지를 읽는다. `category` 기본값은 `all`.
- **`player_name` 채움:** UUID만 있는 모듈 이벤트도 발행 서버가 이름을 찾아 넣는다 (디스코드 `<player>`에 이름이 찍힌다).
- **`/v1/server`:** `online`이 네트워크 전체(하트비트 합계)가 되고, `online_scope`·`servers_reporting`·`server_online`(이 서버)이 생겼다.
- **입력 검사 통일:** 선택형 파라미터(`currency`·`scope`·`category`·`league`)와 정수 파라미터가 틀리면 모두 400 `bad_parameter`(`error.param`, `error.allowed`). 오류 코드 `bad_currency`·`bad_league`는 없어졌다.
- **없는 플레이어 404:** ledger·collection·quests도 UUID로 불렀을 때 players 행이 없으면 404 `player_not_found`.
- **`jobs.advanced`:** `tier`가 숫자(1~3)이고 `tier_key`(`first`·`second`·`third`)가 생겼다. 디스코드 문구가 "2차 전직"으로 찍힌다.
- **`server.content_reloaded`:** 의미 없던 `snapshot`을 빼고 `entry_counts`(다시 읽은 형식별 항목 수)를 넣었다.
- **quests 중복 제거:** 퀘스트마다 한 줄(메인은 가장 최근 시즌 행), `main` 필드와 위쪽 `season` 추가.
- **`/v1/events` 커서:** 응답에 `server`(seq를 붙인 서버)와 `next`가 생겼다. `after`는 int64.

## Source map

유지보수용. 이 문서와 openapi.yaml, events.md는 아래 코드를 기준으로 한다.

| 내용 | 파일 |
|---|---|
| 플러그인 시작, 설정 읽기, 라우트 등록 조건, `server.started`/`stopping`, 관리 명령 | `harbor-web/src/main/java/wiki/creeper/harbor/web/HarborWebPlugin.java` |
| HTTP 서버, 인증, CORS, 처리 순서, 응답 봉투 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/ApiServer.java` |
| 경로 매칭, 쿼리 파싱·검사 (`bad_parameter`) | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/Router.java` |
| 5초 타임아웃, 503/500 변환 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/Calls.java` |
| 속도 제한 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/RateLimiter.java` |
| `/v1/events` 버퍼·커서 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/RecentEvents.java` |
| 네트워크 접속 인원 (하트비트) | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/NetworkOnline.java` |
| Redis 이벤트 읽기(정수 보존), snake_case 이벤트 | `harbor-web/src/main/java/wiki/creeper/harbor/web/relay/EventWire.java` |
| server, players/*, events, content/fish 경로 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/CoreRoutes.java` |
| market, auctions, records, rankings 경로 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/ModuleRoutes.java` |
| 이벤트 카탈로그 (type ↔ 클래스) | `harbor-web/src/main/java/wiki/creeper/harbor/web/relay/EventCatalog.java` |
| 이벤트 구독·발행, 접속/퇴장, `_name` 찾기, `player_name` 찾기 | `harbor-web/src/main/java/wiki/creeper/harbor/web/relay/EventRelay.java` |
| 이벤트 data 변환 | `harbor-web/src/main/java/wiki/creeper/harbor/web/relay/EventSerializer.java` |
| glob | `harbor-web/src/main/java/wiki/creeper/harbor/web/relay/Globs.java` |
| 웹훅 발송, 리더 잠금, 재시도, 디스코드 본문 | `harbor-web/src/main/java/wiki/creeper/harbor/web/webhook/WebhookDispatcher.java` |
| 웹훅 필터, 평탄화, 서명, 대기 시간 | `harbor-web/src/main/java/wiki/creeper/harbor/web/webhook/WebhookRules.java` |
| 웹훅 대상 설정 | `harbor-web/src/main/java/wiki/creeper/harbor/web/webhook/WebhookTarget.java` |
| 기본 설정 | `harbor-web/src/main/resources/config.yml` |
| 이벤트 레코드 | `harbor-common/src/main/java/wiki/creeper/harbor/common/event/ExternalEvent.java` |
| Redis 봉투·채널·발행 | `harbor-common/src/main/java/wiki/creeper/harbor/common/redis/{Envelope,Channels,MessageBus}.java` |
| JSON 설정 (Gson, null 생략) | `harbor-common/src/main/java/wiki/creeper/harbor/common/util/Json.java` |
| 외부 이벤트 API·구현 | `harbor-core/src/main/java/wiki/creeper/harbor/core/api/ExternalEvents.java`, `harbor-core/src/main/java/wiki/creeper/harbor/core/event/CoreExternalEvents.java` |
| 원장 조회 | `harbor-core/src/main/java/wiki/creeper/harbor/core/api/{CurrencyService,LedgerRecord}.java`, `harbor-core/src/main/java/wiki/creeper/harbor/core/currency/CurrencyManager.java` |
| 경매 테이블 | `harbor-economy/src/main/resources/db/migration/economy/V1__economy.sql` (`auction_listing`) |
| 시세 | `harbor-economy/src/main/java/wiki/creeper/harbor/economy/api/PriceService.java`, `harbor-economy/src/main/java/wiki/creeper/harbor/economy/service/MarketEngine.java` |
| 최대어 기록 | `harbor-collection/src/main/java/wiki/creeper/harbor/collection/api/{RecordService,RecordEntry}.java`, `harbor-collection/src/main/java/wiki/creeper/harbor/collection/service/RecordBoard.java` |
| 랭킹 | `harbor-season/src/main/java/wiki/creeper/harbor/season/api/{RankingService,RankingBoard,RankEntry,League}.java`, `harbor-season/src/main/java/wiki/creeper/harbor/season/content/RankingBoardDef.java` |
| 하트비트 | `harbor-common/src/main/java/wiki/creeper/harbor/common/proxy/ProxyMessages.java` (`HEARTBEATS`) |
| 테이블 | `harbor-common/.../db/migration/common/V1__common_tables.sql`(players, ledger), `harbor-core/.../db/migration/core/V1__core_tables.sql`(player_season), `harbor-collection/.../db/migration/collection/V1__collection.sql`(codex_*), `harbor-quest/.../db/migration/quest/V1__quest.sql`(quest_progress) |
| 모듈 이벤트 클래스 | `harbor-<모듈>/src/main/java/wiki/creeper/harbor/<모듈>/api/event/*.java` |
| 디스코드 문구 | `content/messages_ko.yml` (`web.discord.*`) |

코드를 바꾸면 이 세 문서도 같이 고친다. 이벤트 type 목록은 `EventCatalog`와 `WebTest`가 기준이다.
