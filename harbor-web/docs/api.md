# Harbor-Web REST API 명세

웹 프런트엔드·백엔드 개발자용 문서다. 기계가 읽는 명세는 [openapi.yaml](openapi.yaml)(OpenAPI 3.1), 이벤트·웹훅은 [events.md](events.md)에 있다. 모두 코드를 기준으로 썼고, 코드와 다르면 코드가 맞다 (코드 위치는 [Source map](#source-map)).

## 1. 개요

- **무엇:** 크리퍼 항구 게임 서버(Purpur) 안에서 도는 Harbor-Web 플러그인이 여는 **읽기 전용** HTTP API다. 쓰기(지급, 경매 등록 등)는 없다. 모든 경로는 `GET`이다.
- **어디서:** `config.yml http.enabled: true`인 서버에서만 열린다. 운영 의도는 한 서버(예: lobby)에서만 켜는 것이다. 여러 서버에서 켜도 동작은 하지만 서로 독립이다 (특히 `/v1/events`의 seq는 서버마다 다르다). REST에는 리더 잠금이 없다 (잠금은 웹훅에만 있다).
- **주소:** `http://<http.bind>:<http.port>/v1/…`. 기본 `127.0.0.1:8765`. HTTPS는 지원하지 않으므로 앞에 리버스 프록시(nginx, Caddy 등)를 두고 TLS를 끝낸다. 밖에서 직접 받으려면 `bind: 0.0.0.0`.
- **구현:** JDK 내장 `HttpServer` + 요청마다 가상 스레드. DB와 모듈 캐시를 읽는 동안 게임 메인 스레드를 막지 않는다. 메인 스레드에서만 읽을 수 있는 값은 메인 스레드에 맡기고 **최대 5초** 기다린다 (넘으면 503).
- **데이터 신선도:**
  - 플레이어 자료(요약·도감·퀘스트·원장)는 DB에서 읽는다. 도감·퀘스트 진행, 레벨·경험치·플레이 시간은 게임 서버가 지연 쓰기(write-behind, 키별 마지막 값을 주기적으로 씀)하므로 **접속 중인 플레이어는 몇 분 늦을 수 있다.** 재화(골드·물비늘 잔액과 원장)는 즉시 트랜잭션이라 늦지 않다.
  - 시세·기록·랭킹은 그 모듈의 메모리 캐시를 읽는다 (랭킹은 기본 10분 주기 집계).

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
```

- 분기는 `error.code`로 한다. `message`는 사람이 읽는 한국어이고 바뀔 수 있다.
- **null 값은 키째 빠진다** (Gson 기본). 선택 필드는 "없으면 키가 없다"로 다룬다.
- 캐시 헤더(`Cache-Control`, `ETag`, `Last-Modified`)는 없다. 필요하면 앞단 프록시나 웹 백엔드에서 캐시한다.
- 500·503의 원인(SQL 오류 등)은 서버 로그에만 남고 응답에는 들어가지 않는다.

| HTTP | code | 언제 |
|---|---|---|
| 400 | `bad_request` | 경로·쿼리의 퍼센트 인코딩이 잘못됨 (`%zz`) |
| 400 | `bad_id` | `{id}`가 UUID도 마인크래프트 이름(`[A-Za-z0-9_]{1,16}`)도 아님 |
| 400 | `bad_currency` | ledger의 `currency`가 `gold`·`mulbineul`이 아님 |
| 400 | `bad_league` | records·rankings의 `league`가 `tier1`·`tier2`·`all`이 아님 |
| 401 | `unauthorized` | 토큰 없음·틀림 |
| 404 | `not_found` | 등록되지 않은 경로 (모듈이 꺼진 경우 포함) |
| 404 | `player_not_found` | 이름으로 플레이어를 못 찾음, 또는 요약 경로에서 players 행이 없음 |
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
- 정수 파라미터가 숫자가 아니거나 int 범위를 넘으면 **기본값**을 쓴다 (400이 아니다). 숫자면 최소·최대로 잘라 쓴다 (`limit=0` → 1, `limit=9999` → 최대).
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
| 리그 | 쿼리는 소문자 `tier1`·`tier2`·`all`. 랭킹 응답의 `league`만 대문자 `TIER1`·`TIER2`·`ALL` |
| 필드 이름 대소문자 | Harbor-Web이 직접 만드는 응답은 snake_case, 모듈 레코드를 그대로 내보내는 응답(**ledger, auctions, records, rankings/{board}**, events의 이벤트 봉투)은 camelCase다. 경로별 스키마를 따른다 |
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
# {"ok":true,"data":{"server":"lobby-1","season":1,"online":12}}
```

`online`은 **REST가 열린 서버 한 대**의 접속 인원이다. 네트워크 전체 인원은 이 API로 알 수 없다 ([Open questions](#open-questions)).

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

- `{id}` 해석 (모든 `/players/{id}…` 공통): UUID 형식이면 UUID로, 아니면 `[A-Za-z0-9_]{1,16}`인 이름으로 보고 players 테이블에서 `last_join`이 가장 최근인 행을 찾는다. 이름 비교의 대소문자 구분은 DB 콜레이션을 따른다. 형식이 둘 다 아니면 400 `bad_id`, 이름을 못 찾으면 404 `player_not_found`.
- 이 경로는 UUID로 불러도 players 행이 없으면 404다.
- `season`: 이번 시즌 행이 없으면(이번 시즌에 접속한 적 없음) 키가 없다. `job_line`은 2차 전직 전이면 키가 없다 (`explore`·`collect`·`produce`·`economy`). `job_tier`는 1~3.
- `playtime_seconds`는 누적 초. 접속 중이면 마지막 저장 시점까지다.

### GET /v1/players/{id}/ledger

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$HARBOR/v1/players/Steve/ledger?currency=mulbineul&limit=20"
```

| 파라미터 | 기본 | 범위 |
|---|---|---|
| `currency` | `gold` | `gold`·`mulbineul` (그 밖은 400 `bad_currency`) |
| `limit` | 50 | 1~200 |

```json
{"ok":true,"data":[
  {"id":99812,"currency":"GOLD","delta":1520,"balanceAfter":125400,"reason":"FISH_SELL","refId":"sell:4f1c2a","createdAt":"2026-11-20T11:57:41.302Z"}]}
```

- 원장 id 내림차순(최신순). 시즌 구분 없이 전 기간이다. 페이지 넘기기(offset·커서)는 없다. 최대 200행.
- 필드는 camelCase. `currency`는 대문자. `reason`은 `LedgerReason` 이름(대문자)이고 예전 사유가 남아 있을 수 있으니 모르는 값도 처리한다. `refId`는 없으면 키가 없다.
- 진주(PEARL)는 이 경로로 볼 수 없다.
- UUID로 부르면 플레이어 존재를 확인하지 않는다 (없는 UUID → 빈 배열).

### GET /v1/players/{id}/collection

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$HARBOR/v1/players/Steve/collection?scope=season"
```

| 파라미터 | 기본 | 값 |
|---|---|---|
| `scope` | `total` | `season`이면 이번 시즌, 그 밖은 모두 `total` (400 없음) |

```json
{"ok":true,"data":{"scope":"total","registered":2,"total":120,"percent":1.7,"entries":[
  {"fish":"harbor:fish/harbor_mackerel","first_caught":"2026-10-01T09:30:00Z","max_size":41.2,"best_quality":4,"catch_count":57,"fish_name":"항구 고등어"}]}}
```

- `total`은 현재 콘텐츠의 어종 수, `percent`는 소수 첫째 자리 (`total`이 0이면 `0`).
- `entries` 순서는 정해지지 않았다. 화면에서는 `GET /v1/content/fish`의 `no`로 정렬한다.
- `fish_name`은 콘텐츠에서 빠진 어종이면 없다.
- 지연 쓰기 때문에 접속 중인 플레이어는 몇 분 늦을 수 있다. UUID로 부르면 존재를 확인하지 않는다.

### GET /v1/players/{id}/quests

```bash
curl -s -H "Authorization: Bearer $TOKEN" $HARBOR/v1/players/Steve/quests
```

```json
{"ok":true,"data":{"quests":[
  {"quest":"harbor:quest/main/ch1_first_cast","season":1,"state":"completed","updated_at":"2026-10-01T10:02:11Z","name":"첫 캐스팅"}]}}
```

- 이번 시즌의 모든 퀘스트 행 + **시즌과 상관없이** `harbor:quest/main/`으로 시작하는 메인 퀘스트 행. 메인 퀘스트가 여러 시즌에 걸쳐 있으면 같은 `quest`가 시즌별로 여러 번 나올 수 있다.
- `state`: `active`·`ready`(목표 달성, 제출 가능)·`completed`·`failed`(기간 종료). 포기한 퀘스트는 행이 지워져 없다. 아직 받지 않은 퀘스트(잠김·수락 가능)도 나오지 않는다.
- 이름 키는 `name`이다 (이벤트의 `quest_name`과 다름). 진행도(목표별 수치)는 주지 않는다.
- 순서는 정해지지 않았다.

### GET /v1/events

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$HARBOR/v1/events?after=1040&type=collection.*&limit=50"
```

| 파라미터 | 기본 | 범위 |
|---|---|---|
| `after` | 0 | 0~2147483647. 이 seq보다 큰 것만 |
| `type` | (전부) | type glob, `*`만 와일드카드 |
| `limit` | 100 | 1~500 |

```json
{"ok":true,"data":{"last":1042,"items":[
  {"seq":1042,"event":{"id":"a8d2…","type":"collection.record_broken","version":1,"server":"main-1",
   "occurredAt":"2026-11-20T12:00:05.901Z","player":"069a…","data":{"fish":"harbor:fish/harbor_mackerel","size":41.2,"…":"…"}}}]}}
```

폴링 방법:
1. 처음에는 `after=0`(버퍼 전체) 또는 현재 `last`를 얻어 그 뒤부터 읽는다.
2. 받은 `items`의 마지막 `seq`를 저장하고 다음 요청의 `after`로 쓴다. `items`가 비면 `after`를 유지한다. (`type` 필터를 쓰면 `last`가 아니라 마지막 item의 seq를 써야 필터에 맞는 이벤트를 놓치지 않는다. 필터 없이 다 받았다면 `last`를 써도 된다.)
3. `last < after`이면 REST 서버가 재시작된 것이다 (seq가 1부터 다시). `after=0`으로 다시 읽고, 이미 처리한 것은 `event.id`로 거른다.

주의:
- 버퍼는 REST 서버 메모리에 최근 `recent_events`개(기본 500)만 있다. 폴링 간격 동안 그보다 많이 쌓이면 **빠진 이벤트를 알려 주지 않고** 잃는다. `fishing.caught`·`economy.currency_changed`가 많으면 버퍼가 빨리 돈다.
- seq는 REST 서버가 받은 순서다. 서버 간 실제 발생 순서와 다를 수 있다 (`occurredAt` 참고).
- `event.data`의 숫자는 정수도 `13.0`처럼 실수로 나온다. 이벤트별 data 필드는 [events.md](events.md#3-이벤트-카탈로그).

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
| `category` | (전체) | `fish`·`gear`·`bait`·`material`·`food`·`other`. 검사하지 않으므로 그 밖의 값은 빈 배열 |
| `limit` | 50 | 1~200으로 받지만 **실제 최대 45** |

```json
{"ok":true,"data":[{"id":5120,"seller":"069a…","sellerName":"Steve","itemId":"harbor:fish/silver_anchovy","itemName":"은빛 멸치",
  "category":"fish","amount":1,"price":4200,"expiresAt":"2026-11-22T11:00:00Z"}]}
```

- 최신 등록순, 만료되지 않은 진행 중 목록. camelCase. `price`는 묶음 전체 골드.
- 경매 화면의 첫 페이지(45개)를 재사용하므로 45개를 넘게 받을 수 없고, 다음 페이지도 없다.
- 카테고리별로 `balance.yml economy.auction.cache_s`(기본 30초) 캐시된다.

### GET /v1/records

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$HARBOR/v1/records?fish=harbor:fish/harbor_mackerel&league=all&limit=5"
# {"ok":true,"data":[{"player":"069a…","name":"Steve","size":41.2,"caughtAt":"2026-11-20T12:00:05.901Z"}]}
```

| 파라미터 | 기본 | 값 |
|---|---|---|
| `fish` | (필수) | `harbor:fish/<이름>` 또는 `fish/<이름>`. 없거나 틀리면 404 `fish_not_found` |
| `league` | `all` | `tier1`·`tier2`·`all` (그 밖은 400 `bad_league`) |
| `limit` | 10 | 1~10 |

이번 시즌의 그 어종 최대어 상위(크기 내림차순), camelCase. 기록이 없으면 빈 배열. 리그는 기록 당시 직업 단계로 정해진다.

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
{"ok":true,"data":{"board":"level","league":"TIER1","season":1,"computedAt":"2026-11-20T12:00:00.004Z","entries":[
  {"rank":1,"uuid":"069a…","name":"Steve","value":52.0,"secondary":18200.0,"achievedAt":"2026-11-19T22:41:10Z","members":1}]}}
```

- 집계 캐시(보드별 `refresh`, 기본 10분)를 읽는다. `computedAt`으로 집계 시각을 보여 줄 수 있다.
- 아직 집계 전이거나 그 보드가 이 리그를 집계하지 않으면 `entries: []`, `computedAt: "1970-01-01T00:00:00Z"`.
- camelCase이고 `league`는 대문자. `value` 단위는 보드의 source에 따른다 (최대어 cm, 레벨, 도감 수, 보스 처치 시간 ms 등). `uuid`는 없으면 키가 없다. 동점은 먼저 달성한 사람이 위다.

## 9. 웹 백엔드 구현 권장

- Harbor-Web은 게임 서버 안에서 돈다. 웹 트래픽을 그대로 넘기지 말고 웹 백엔드에서 캐시한다 (`content/fish` 몇 시간, `market`·`rankings` 수 분, 플레이어 자료 30초~수 분).
- 실시간 알림은 폴링보다 웹훅(`json`) 또는 Redis 구독을 쓴다 ([events.md](events.md)).
- 오류는 `error.code`로 분기하고, 503은 잠시 뒤 재시도, 429는 다음 분까지 대기한다.

## Open questions

코드에서 확인한 동작 중 의도가 불분명하거나 웹 팀이 알아야 할 제약이다. 고칠지 여부는 서버 팀이 정한다.

1. **필드 이름 표기가 섞여 있다.** Harbor-Web이 만든 응답은 snake_case, 모듈 레코드를 그대로 내보내는 ledger·auctions·records·rankings/{board}는 camelCase다. 또 `currency`(`GOLD`)와 랭킹 `league`(`ALL`)는 대문자 enum 이름인데 쿼리와 다른 응답은 소문자다. 지금 명세는 현재 동작을 그대로 적었다. 통일하면 하위 호환이 깨진다.
2. **`/v1/auctions`의 `limit`은 200까지 받지만 실제 최대 45**이고 다음 페이지가 없다.
3. **`/v1/events`의 data 숫자가 실수로 나온다** (`13.0`). Redis 원본은 정수다. REST 서버가 Gson 기본 숫자 정책(DOUBLE)으로 다시 읽기 때문이다. 웹훅 `json` 본문도 같다.
4. **이벤트의 `playerName`이 대부분 없다.** 모듈 이벤트 대부분이 Player가 아니라 UUID를 주기 때문이다. 디스코드 문구의 `<player>`에도 UUID가 찍힌다 ([events.md](events.md#discord)).
5. **`/v1/server`의 `online`은 REST 서버 한 대 기준이다.** 네트워크 전체 인원 경로는 없다.
6. **잘못된 값의 처리가 경로마다 다르다.** `scope`·`category`는 검사하지 않고(기본값/빈 배열), `currency`·`league`는 400, 정수 파라미터는 숫자가 아니면 조용히 기본값이다.
7. **UUID로 부른 하위 경로(ledger·collection·quests)는 플레이어 존재를 확인하지 않는다** (빈 결과 200). 요약 경로만 404를 준다.
8. **도감 테이블 이름이 아직 `codex_total`/`codex_season`이다** (모듈 이름은 collection으로 바뀜). API 응답에는 영향이 없다. 랭킹 보드 ID에도 `codex`가 남아 있다 (`content/season/rankings.yml`). 보드 ID는 콘텐츠 값이라 바뀌면 `/v1/rankings/{board}` 경로가 바뀐다.
9. **`jobs.advanced` 이벤트의 `tier`가 `"second"` 같은 enum 이름이다.** 디스코드 문구 `<tier>차 전직`이 "second차"로 찍힌다.
10. **`server.content_reloaded`의 `snapshot` 값은 Java 객체 문자열이라 의미가 없다.**
11. **이름으로 찾을 때의 대소문자 구분**은 DB 콜레이션에 달려 있다 (코드는 `name = ?` 비교만 한다).
12. **같은 메인 퀘스트가 시즌별로 여러 행** 나올 수 있다 (`/players/{id}/quests`). 의도인지 확인 필요.
13. **REST를 여러 서버에서 켜면** `/v1/events`의 seq가 서버마다 달라 로드밸런서 뒤에 두면 커서가 깨진다. 한 서버만 켜거나, 고정 라우팅을 쓴다.
14. **버전 정책:** 경로 접두사 `/v1` 말고 응답 형식 버전 표시는 없다. 응답 필드 추가는 예고 없이 될 수 있다고 가정하고, 모르는 필드는 무시하게 만든다.

## Source map

유지보수용. 이 문서와 openapi.yaml, events.md는 아래 코드를 기준으로 한다.

| 내용 | 파일 |
|---|---|
| 플러그인 시작, 설정 읽기, 라우트 등록 조건, `server.started`/`stopping`, 관리 명령 | `harbor-web/src/main/java/wiki/creeper/harbor/web/HarborWebPlugin.java` |
| HTTP 서버, 인증, CORS, 처리 순서, 응답 봉투 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/ApiServer.java` |
| 경로 매칭, 쿼리 파싱, 정수 파라미터 자르기 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/Router.java` |
| 5초 타임아웃, 503/500 변환 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/Calls.java` |
| 속도 제한 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/RateLimiter.java` |
| `/v1/events` 버퍼 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/RecentEvents.java` |
| server, players/*, events, content/fish 경로 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/CoreRoutes.java` |
| market, auctions, records, rankings 경로 | `harbor-web/src/main/java/wiki/creeper/harbor/web/http/ModuleRoutes.java` |
| 이벤트 카탈로그 (type ↔ 클래스) | `harbor-web/src/main/java/wiki/creeper/harbor/web/relay/EventCatalog.java` |
| 이벤트 구독·발행, 접속/퇴장, `_name` 찾기 | `harbor-web/src/main/java/wiki/creeper/harbor/web/relay/EventRelay.java` |
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
| 경매 조회 | `harbor-economy/src/main/java/wiki/creeper/harbor/economy/api/{AuctionService,AuctionListingInfo}.java`, `harbor-economy/src/main/java/wiki/creeper/harbor/economy/service/{AuctionHouse,AuctionListings}.java` |
| 시세 | `harbor-economy/src/main/java/wiki/creeper/harbor/economy/api/PriceService.java`, `harbor-economy/src/main/java/wiki/creeper/harbor/economy/service/MarketEngine.java` |
| 최대어 기록 | `harbor-collection/src/main/java/wiki/creeper/harbor/collection/api/{RecordService,RecordEntry}.java`, `harbor-collection/src/main/java/wiki/creeper/harbor/collection/service/RecordBoard.java` |
| 랭킹 | `harbor-season/src/main/java/wiki/creeper/harbor/season/api/{RankingService,RankingBoard,RankEntry,League}.java`, `harbor-season/src/main/java/wiki/creeper/harbor/season/content/RankingBoardDef.java` |
| 테이블 | `harbor-common/.../db/migration/common/V1__common_tables.sql`(players, ledger), `harbor-core/.../db/migration/core/V1__core_tables.sql`(player_season), `harbor-collection/.../db/migration/collection/V1__collection.sql`(codex_*), `harbor-quest/.../db/migration/quest/V1__quest.sql`(quest_progress) |
| 모듈 이벤트 클래스 | `harbor-<모듈>/src/main/java/wiki/creeper/harbor/<모듈>/api/event/*.java` |
| 디스코드 문구 | `content/messages_ko.yml` (`web.discord.*`) |

코드를 바꾸면 이 세 문서도 같이 고친다. 이벤트 type 목록은 `EventCatalog`와 `WebTest`가 기준이다.
