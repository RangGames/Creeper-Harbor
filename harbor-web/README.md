# Harbor-Web 사용법

외부 연동 모듈이다. 하는 일은 세 가지다.

1. 게임 이벤트를 Redis `harbor:events`로 발행한다 (모든 서버).
2. 웹훅으로 디스코드나 JSON POST를 보낸다 (한 서버).
3. 읽기 전용 REST API를 연다 (한 서버).

설정은 `plugins/Harbor-Web/config.yml`이다. 웹훅 URL과 토큰 같은 비밀이 들어가므로 저장소에는 넣지 않는다.

## 구성

```
[각 게임 서버] 모듈 이벤트 ─(Harbor-Web EventRelay)─▶ api.events().emit ─▶ Redis harbor:events
                                                                        │
          ┌─────────────────────────────────────────────────────────────┤
          ▼                                         ▼                   ▼
[웹훅 서버] WebhookDispatcher               [REST 서버] RecentEvents   [외부 서비스] 직접 SUBSCRIBE
  (Redis 잠금으로 한 서버만 발송)              GET /v1/events
```

- **이벤트 발행:** 모든 서버에서 켠다 (`events.publish: true`, 기본값).
- **웹훅:** 한 서버에서 켜면 된다. 여러 서버에서 켜도 `harbor:lock:webhook` 잠금을 잡은 서버만 보낸다.
- **REST:** 한 서버(예: lobby)에서 켠다. 앞에 HTTPS 리버스 프록시를 두고, 기본 bind는 `127.0.0.1`이다.

## 이벤트 형식

Redis 메시지는 Harbor 공통 봉투이고 `payload`가 이벤트다.

```json
{"type":"ExternalEvent","server":"main-1","sentAt":"2026-11-20T12:00:00Z",
 "payload":{"id":"3f0c…","type":"collection.record_broken","version":1,"server":"main-1",
            "occurredAt":"2026-11-20T12:00:00Z","player":"<uuid>","playerName":"Steve",
            "data":{"fish":"harbor:fish/harbor_mackerel","fish_name":"고등어","league":"all",
                    "scope":"server","size":41.2,"previous":39.8,"player":"<uuid>"}}}
```

- **id:** 이벤트 고유 ID다. 받는 쪽에서 중복을 거를 때 쓴다.
- **version:** data 형식 버전이다. 필드를 빼거나 뜻을 바꿀 때만 올리고, 필드 추가는 그대로 1이다.
- **data 필드 이름:** 이벤트 getter 이름을 snake_case로 바꾼 것이다 (`getSizeCm` → `size_cm`).
- **값 변환:**
  - ID는 `harbor:<종류>/<이름>` 글이 된다. 콘텐츠 이름을 찾으면 `<필드>_name`을 함께 넣는다.
  - 레코드는 지도가 된다. 콘텐츠 정의(어종 등)는 `{id, no, name, grade}`만 넣는다.
  - enum은 소문자 키, 위치는 `{world,x,y,z}`, 아이템은 `{material, amount}`가 된다.
- **다른 플러그인에서 발행:** `Harbor.api().events().emit("skript.lottery_won", uuid, name, Map.of("prize", 1000))`. type 형식은 `모듈.사건`(소문자·밑줄)이다.

### type 목록

| 모듈 | type |
|---|---|
| server | `server.started` `server.stopping` `server.player_join` `server.player_quit` `server.content_reloaded` `server.webhook_test` |
| Core | `economy.currency_changed` |
| Quest | `quest.accepted` `quest.completed` `quest.abandoned` `quest.chapter_completed` `quest.chronicle_piece` |
| Economy | `economy.auction_listed` `economy.auction_sold` `economy.fish_sold` `economy.item_crafted` `economy.shop_purchase` `economy.trade_flagged` `economy.market_updated` |
| Collection | `collection.codex_registered` `collection.codex_milestone` `collection.record_broken` `collection.aquarium_donated` `collection.aquarium_stage` `collection.fish_released` |
| Fishing | `fishing.caught` `fishing.trap_harvest` |
| Jobs | `jobs.advanced` `jobs.level_up` `jobs.line_changed` `jobs.capstone_used` |
| Npc | `npc.reputation_tier` `npc.affinity_level_up` `npc.gift_given` |
| Season | `season.title_granted` `season.week_changed` `season.daily_reset` `season.weekly_reset` `season.hall_of_fame` `season.player_entered` |
| Shop | `shop.cosmetic_granted` `shop.donor_tier_changed` `shop.season_pass_level_up` `shop.festival_progress` |
| Boss | `boss.encounter_start` `boss.encounter_end` `boss.world_progress` |
| World | `world.sea_event_warn` `world.sea_event_start` `world.sea_event_end` `world.treasure_claimed` `world.ship_wrecked` `world.region_first_visit` `world.plot_liked` `world.chart_progress` |

- **목록의 출처:** `relay/EventCatalog`에 있다. 그 모듈이 켜져 있을 때만 구독한다. 클래스가 실제로 있는지는 단위 테스트로 확인한다.
- **빈도가 높은 이벤트:** `fishing.caught`와 `economy.currency_changed`는 자주 난다. 필요 없으면 `events.exclude`에 넣는다.

## 웹훅

```yaml
webhooks:
  enabled: true
  targets:
    - name: discord-announce
      url: https://discord.com/api/webhooks/…
      format: discord
      events: [quest.chapter_completed, collection.record_broken, fishing.caught]
      where: {fish.grade: legendary}      # data 조건 (점으로 이은 경로)
    - name: backend
      url: https://example.com/harbor/events
      format: json
      secret: change-me
      events: ["*"]
```

- **discord:** `messages_ko.yml`의 `web.discord.<type>` 문구로 보낸다. 문구가 없는 type은 보내지 않는다. 자리표시자는 `<player>` `<server>` `<type>`와 data의 모든 칸이다 (점은 밑줄: `fish.name` → `<fish_name>`). 멘션은 막는다.
- **json:** 이벤트(payload) 전체를 POST한다. 헤더는 다음과 같다.
  - `X-Harbor-Event`: type
  - `X-Harbor-Id`: 이벤트 id
  - `X-Harbor-Timestamp`: 초 단위 유닉스 시각
  - `X-Harbor-Signature`: `sha256=` + hex(HMAC-SHA256(secret, timestamp + "." + body)). 받는 쪽은 같은 값을 계산해 고정 시간 비교하고, 오래된 timestamp는 거절한다.
- **재시도:**
  - 실패하면 1초, 5초, 15초 뒤에 다시 보낸다 (`retries`).
  - 429면 `retry_after`만큼 기다린다. 4xx(429 제외)는 다시 보내지 않는다.
  - 대기열(`queue_size`)이 차면 새 이벤트를 버리고 경고를 남긴다.
- **관리 명령:** `/harbor web status`, `/harbor web test <대상>`, `/harbor web events` (REST 서버에서 최근 10개).

## REST API

`Authorization: Bearer <토큰>`이 필요하다 (`/v1/health`만 예외). 토큰마다 분당 요청 수를 제한한다 (`rate_limit_per_minute`). 응답 형식은 `{"ok":true,"data":…}` 또는 `{"ok":false,"error":{"code","message"}}`이다. 경로 목록은 `GET /v1`로도 볼 수 있다.

| 경로 | 내용 |
|---|---|
| `GET /v1/health` | 살아 있는지 (토큰 불필요) |
| `GET /v1/server` | 서버 ID·시즌·접속 인원 |
| `GET /v1/players/{uuid 또는 이름}` | 이름·접속 시각·플레이 시간 + 이번 시즌 레벨·직업·골드·물비늘·평판 |
| `GET /v1/players/{id}/codex?scope=total\|season` | 도감 (등록 수·%·어종별 최대 크기·최고 품질·횟수) — Collection |
| `GET /v1/players/{id}/quests` | 퀘스트 상태 (이번 시즌 + 메인) — Quest |
| `GET /v1/players/{id}/ledger?currency=gold\|mulbineul&limit=50` | 재화 원장 최근 기록 (사유·증감·잔액·시각, 최대 200) |
| `GET /v1/events?after=<seq>&type=<glob>&limit=100` | 최근 외부 이벤트 (`last`를 다음 요청의 `after`로) |
| `GET /v1/content/fish` | 어종 목록 |
| `GET /v1/market` | 어종 시세 계수와 다음 시간 예측 — Economy |
| `GET /v1/auctions?category=&limit=50` | 경매 진행 중 목록 — Economy |
| `GET /v1/records?fish=<어종 ID>&league=all&limit=10` | 어종 서버 기록 TOP — Collection |
| `GET /v1/rankings` · `GET /v1/rankings/{board}?league=all` | 랭킹 보드와 순위표 (10분 주기 집계 캐시) — Season |

- **자료 출처:** 플레이어 자료는 DB에서 읽기만 한다. 접속 중인 플레이어는 지연 쓰기 때문에 몇 분 늦을 수 있다.
- **모듈 자료:** 시세·기록·랭킹은 그 모듈의 캐시를 메인 스레드에서 읽는다.
- **응답 시간:** 5초 안에 답하지 못하면 503이다.

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/v1/players/Steve
```
