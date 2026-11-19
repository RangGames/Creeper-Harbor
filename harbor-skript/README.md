# Harbor-Skript

크리퍼 항구 플러그인(Harbor)을 [Skript](https://github.com/SkriptLang/Skript)에서 쓰기 위한 애드온입니다.
골드·물비늘·Harbor 아이템·문구를 스크립트에서 다룰 수 있고, 모든 변경은 Harbor의 규칙(원장, 아이템 태그)을 거칩니다.

- 기준 버전: Skript **2.17.1** (2.17 등록 API 사용)
- Skript가 없으면 아무것도 하지 않습니다 (서버 오류 없이 꺼진 채로 둠)

## 설치

1. 문법을 쓸 서버(lobby·play)마다 `Skript.jar`와 `harbor-skript.jar`를 `plugins/`에 넣습니다.
2. 서버를 켜면 로그에 `Skript에 Harbor 문법 8개를 등록했습니다`가 찍힙니다.
3. 스크립트는 `plugins/Skript/scripts/*.sk`에 둡니다 (Skript 기본 방식).

## 꼭 지킬 것

| 하지 말 것 | 이유 |
|---|---|
| Skript 변수(`{...}`)에 진행도·보상 지급 기록 저장 | Skript 변수는 서버마다 따로 저장됩니다. lobby와 play가 플레이어를 넘겨받는 구조라 값이 어긋나거나 중복 지급이 생깁니다 |
| 바닐라 `give`로 Harbor 아이템 흉내 내기 | Harbor 태그가 없는 아이템은 상점·납품·도감에서 인식되지 않습니다. `harbor item`을 쓰세요 |
| `on chat`으로 채팅 가공 | Harbor가 채팅을 서버에서 취소하고 프록시로 보냅니다. 형식은 `messages_ko.yml`의 `chat.format`에서 바꿉니다 |
| `on any move` · `every tick` 같은 잦은 이벤트에 무거운 처리 | 스크립트는 메인 스레드에서 돕니다 |

재화를 바꿀 때는 아래 `harbor gold`·`harbor mulbineul`만 쓰세요. 진주(유료 재화)는 스크립트에서 다루지 않습니다.

## 문법

### 골드 · 물비늘

```
[the] harbor (gold|mulbineul) of %players%
%players%'[s] harbor (gold|mulbineul)
```

- **읽기**: 이번 시즌 잔액(정수). **이 서버에 접속한 플레이어만** 값이 있습니다.
- **바꾸기**: `add` · `remove` · `set`. 원장에 사유 `SCRIPT_GRANT`(늘림) · `SCRIPT_TAKE`(줄임)로 기록됩니다.
  - 처리는 비동기라 바로 다음 줄에서 읽으면 아직 이전 값일 수 있습니다. 결과를 확인하려면 `on harbor currency change`를 쓰세요.
  - 잔액이 모자라면 `remove`는 아무것도 빼지 않습니다 (음수 잔액 없음).
  - `set`은 지금 캐시 잔액과의 차이만큼 늘리거나 줄입니다.

```
command /출석:
    trigger:
        add 100 to harbor gold of player
        send "&a출석 보상 100G" to player

if harbor gold of player >= 500:
    remove 500 from harbor gold of player
```

### Harbor 아이템 만들기

```
harbor item %string%
```

ID 앞부분으로 종류를 고릅니다. `harbor:`는 생략할 수 있습니다.

| ID | 만들어지는 것 |
|---|---|
| `item/…` | Harbor 아이템 (`content/items/*.yml`) |
| `furniture/…` | 가구 (`content/furniture/furniture.yml`) |
| `block/…` | 커스텀 블록 (`content/furniture/blocks.yml`) |

없는 ID면 값이 없습니다(아무것도 주지 않음). 개수는 Skript 문법 `%number% of`로 정합니다.

```
give 5 of harbor item "item/bait/flour_paste" to player
give harbor item "furniture/fishing_chair" to player
give 64 of harbor item "block/marble_tile" to player   # blocks.yml에 정의한 블록
```

### 아이템의 Harbor ID

```
[the] harbor id of %itemstacks%
%itemstacks%'[s] harbor id
```

`harbor:item/…` · `harbor:fish/…`(어종) · `harbor:furniture/…` · `harbor:block/…` 형식 문자열입니다.
Harbor 아이템이 아니면 값이 없습니다.

```
if harbor id of player's tool is "harbor:fish/harbor_mackerel":
    send "고등어를 들고 있네요" to player
```

### Harbor 아이템인지

```
%itemstacks% (is|are) [a] harbor item[s]
%itemstacks% (isn't|is not|aren't|are not) [a] harbor item[s]
```

```
if player's tool is not a harbor item:
    send "Harbor 아이템이 아닙니다" to player
```

### 문구 보내기

```
send harbor message %string% to %players%
```

`messages_ko.yml`의 키를 보냅니다 (MiniMessage, `<glyph:…>` 포함). 자리 표시(`<name>` 등)는 채워지지 않으니
자리 표시가 없는 문구를 쓰거나 스크립트용 키를 따로 만드세요.

```
send harbor message "help.welcome" to player
```

### 서버 이동

```
harbor send %players% to [the] lobby
harbor send %players% to [the] sea
```

- `lobby`: 로비로 보냅니다. 저장 → 세션 넘김을 거치는 정상 이동이고, 본 서버에서 나갔으면 자리 유예(`harbor-proxy.yml` `queue.grace_seconds`)를 받습니다. 로비에 도착해도 자동 출항하지 않습니다.
- `sea`: **출항**입니다. 로비에 있는 플레이어를 본 서버 대기열에 넣습니다 (`/출항`과 같음). 로비가 아니면 아무 일도 없고, 결과(대기 순번 등)는 프록시가 플레이어에게 알립니다.
- 다른 서버로 바로 보내는 문법은 없습니다. 본 서버 입장은 항상 대기열을 거칩니다.

```
harbor server            # 이 서버 ID (예: lobby, play)
harbor server role       # main · lobby · instance
```

Shift+F 메뉴 예시 (로비·본 서버 공통):

```
on swap hand items:
    player is sneaking
    cancel event
    if harbor server role is "lobby":
        harbor send player to sea
    else:
        harbor send player to lobby
```

NPC 대화에서는 액션 `server:depart`(출항) · `server:lobby`(로비로)를 쓸 수 있습니다.

### 이벤트: 재화가 바뀐 뒤

```
on harbor currency change:
```

골드·물비늘 변경이 원장에 기록된 뒤(메인 스레드) 발생합니다. 이 서버에서 처리한 변경만 옵니다. 낚시 판매, 퀘스트 보상, 스크립트 지급 등 모든 사유가 포함됩니다.

| 값 | 내용 |
|---|---|
| `event-offlineplayer` | 대상 플레이어 (오프라인일 수 있음) |
| `event-number` | 변화량 (늘면 양수, 줄면 음수) |
| `event-string` | `gold` 또는 `mulbineul` |

```
on harbor currency change:
    event-string is "gold"
    event-number >= 10000
    broadcast "&6%event-offlineplayer% 님이 한 번에 %event-number%G를 얻었습니다!"
```

## 운영: 스크립트가 지급한 재화 확인

원장에서 사유로 찾습니다.

```sql
SELECT uuid, currency, delta, balance_after, ref_id, created_at
FROM ledger
WHERE reason IN ('SCRIPT_GRANT', 'SCRIPT_TAKE')
ORDER BY id DESC
LIMIT 100;
```

`ref_id`는 `skript:<무작위 UUID>`입니다.

## 문법 추가

새 문법은 `src/main/java/wiki/creeper/harbor/skript/`에 클래스를 만들고 `SkriptSyntax.register`에 등록합니다.
재화·아이템·플레이어 데이터는 반드시 `Harbor.api()`의 서비스로 다룹니다.
