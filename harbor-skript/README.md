# Harbor-Skript

크리퍼 항구 플러그인(Harbor)을 [Skript](https://github.com/SkriptLang/Skript)에서 쓰기 위한 애드온입니다.
골드·물비늘·Harbor 아이템·문구를 스크립트에서 다룰 수 있고, 모든 변경은 Harbor의 규칙(원장, 아이템 태그)을 거칩니다.

- 기준 버전: Skript **2.17.1** (2.17 등록 API 사용)
- Skript가 없으면 아무것도 하지 않습니다 (서버 오류 없이 꺼진 채로 둠)

## 전체 문법 레퍼런스

이 README는 기본 문법과 지킬 것을 다룹니다. 모듈별 전체 문법은 아래 문서에 있습니다.

| 문서 | 다루는 것 |
|---|---|
| [docs/core-jobs-season.md](docs/core-jobs-season.md) | 플레이어 데이터·수치 보정·재화(오프라인·이체·순위·내역)·문구·보스바·다이얼로그(대기형)·소리·서버 이동·파티(조회·변경)·보스 주간 보상 횟수·월드 경계·콘텐츠 리로드·외부 이벤트·리그(이벤트 포함)·가구(놓기·찾기·치우기·이벤트)·커스텀 블록, 직업(스킬·관리자 설정), 시즌·칭호·주간 한도·랭킹·명예의 전당 |
| [docs/fishing-collection-quest-wildlife.md](docs/fishing-collection-quest-wildlife.md) | 낚시(세션·물고기 아이템·통발·장력 미니게임·모든 낚시 이벤트), 도감·수족관·방류·기록·전시함, 퀘스트·연대기·서버 진행도, 야생 물고기 |
| [docs/npc-economy-world-boss-shop.md](docs/npc-economy-world-boss-shop.md) | NPC·대화(스크립트로 대화 액션·조건 구현)·호감도·평판·컷씬, 경제(시세·판매가·경매·제작대), 월드(지역·선박·하우징·해도·보물·해역 이벤트), 보스·파티·기록, 상점(치장품·후원·시즌 패스) |

결과가 늦게 오는 동작(오프라인 잔액, 다이얼로그 답, 칭호 지급 등)은 `… and store it in {_var}` 형태의 대기 문법입니다. 결과를 받을 때까지 그 트리거의 다음 줄을 미루고, 메인 스레드는 막지 않습니다.

## 설치

1. 문법을 쓸 서버(lobby·play)마다 `Skript.jar`와 `harbor-skript.jar`를 `plugins/`에 넣습니다.
2. 서버를 켜면 로그에 `Skript에 Harbor 문법 N개를 등록했습니다`가 찍힙니다. 켜져 있는 Harbor 모듈에 따라 개수가 다릅니다.
   - 아래 문법 중 **(모듈 이름)** 표시가 있는 것은 그 모듈이 켜져 있을 때만 등록됩니다.
   - 꺼진 모듈의 문법을 쓰면 스크립트를 읽을 때 "알 수 없는 문법" 오류가 납니다.
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

## ID 쓰는 법

퀘스트·어종·NPC·컷씬 ID는 `quest/main/ch1_first_cast`처럼 씁니다.

- `harbor:`는 생략할 수 있고, 대소문자는 가리지 않습니다.
- 종류(`quest/` · `fish/` · `npc/` · `cutscene/`)를 빼먹으면 문법이 알아서 붙입니다. 예: `"main/ch1_first_cast"`
- 칭호는 `title/…`입니다.
- 없는 ID나 형식이 틀린 ID면 식은 값이 없고, 효과는 아무것도 하지 않습니다.

## 플레이어 정보

```
[the] harbor level of %players%                %players%'[s] harbor level
[the] harbor job tier of %players%             %players%'[s] harbor job tier      # 1 · 2 · 3
[the] harbor job line of %players%             %players%'[s] harbor job line      # 계열 키 (2차 전직 전이면 없음)
[the] harbor reputation [points] of %players%                                    # 평판 점수
[the] harbor season [number]                                                     # 지금 시즌 번호
```

**(Harbor-Npc)**

```
[the] harbor reputation tier of %players%                       # 평판 등급
[the] harbor affinity [level] of %players% with [npc] %string%  # NPC 호감도 단계
[the] harbor affinity points of %players% with [npc] %string%   # 호감도 점수
```

```
if harbor level of player >= 30:
    if harbor affinity of player with npc "bawi" >= 3:
        send "바위가 당신을 믿기 시작했습니다" to player
```

## 플래그

플레이어 플래그·서버 플래그는 퀘스트 조건, 지역 진입, 상점 해금에서 같이 쓰는 값입니다. 쓰기는 비동기로 DB에 저장되고 모든 서버에 반영됩니다.

- **이름 형식:** 소문자·숫자·밑줄을 `/`로 잇습니다. 예: `event/halloween_2026`
- **충돌 주의:** `quest/…`(퀘스트 완료 표시)·`story/…`(메인 스토리)·`visited/…`(지역 방문) 이름은 Harbor가 씁니다. 스크립트용은 `skript/…`처럼 따로 두세요.

```
%players% (has|have) harbor flag %string%
%players% (doesn't|does not|don't|do not) have harbor flag %string%
[the] harbor flag value of %players% for %string%
harbor set flag %string% for %players% [to %-string%]            # 영구 (값을 비우면 "1")
harbor set seasonal flag %string% for %players% [to %-string%]   # 시즌이 바뀌면 지워짐
harbor (unset|clear) flag %string% (for|of|from) %players%

harbor server flag %string% is set          harbor server flag %string% (isn't|is not) set
[the] harbor server flag value [of] %string%
harbor set server flag %string% [to %-string%]
harbor (unset|clear) server flag %string%
```

```
command /할로윈보상:
    trigger:
        if player has harbor flag "skript/halloween_2026":
            send "이미 받았습니다" to player
            stop
        harbor set flag "skript/halloween_2026" for player
        give harbor item "item/bait/flour_paste" to player
```

플래그 쓰기는 비동기입니다. 바로 다음 줄에서 `has harbor flag`가 아직 거짓일 수 있으니 중복 방지는 위처럼 **쓰기 전에** 확인하세요. 같은 틱에 두 번 부르는 명령이라면 Skript 쿨다운도 함께 두세요.

## 화면 · 우편

```
show harbor title %string% [with subtitle %-string%] to %players%   # MiniMessage, 표시 시간은 balance.yml skript.title_seconds (기본 2.5초)
show harbor action[ ]bar %string% to %players%                      # MiniMessage
send harbor mail %itemstacks% to %offlineplayers%                   # 우편함 (Harbor-Economy가 없으면 아무것도 안 함)
```

```
show harbor title "<gold>대어 출현!" with subtitle "<gray>항구 앞바다에 전설의 그림자가…" to all players
send harbor mail harbor item "item/bait/flour_paste" to player
```

우편은 오프라인 플레이어에게도 보낼 수 있습니다. 인벤토리가 차 있어도 잃어버리지 않습니다.

## 퀘스트 (Harbor-Quest)

```
[the] harbor quest state of %players% for [quest] %string%    # locked · available · active · ready · completing · completed · failed …
[the] (number|amount) of active harbor quests of %players%
%players% (has|have) completed harbor quest %string%
%players% (hasn't|has not|haven't|have not) completed harbor quest %string%

make %players% accept harbor quest %string%     # 조건을 확인하고 받음 (안 되면 Harbor가 이유를 알림)
make %players% abandon harbor quest %string%
make %players% track harbor quest %string%

harbor quest signal %string% [with target %-string%] [by %-number%] for %players%   # 목표 진행 +N (기본 1)
harbor quest set %string% [with target %-string%] to %number% for %players%          # 목표 값을 그 값으로
```

**목표 신호**는 스크립트가 퀘스트 목표를 진행시키는 방법입니다. `signal`의 첫 값은 목표 유형이고, 퀘스트 YAML의 `type`과 같아야 합니다.

- 기본 유형(`catch` · `talk` · `use_item` …)이나 다른 모듈이 등록한 유형을 쓸 수 있습니다.
- target은 YAML의 `target`과 비교합니다. 없으면 `any` 목표만 맞습니다.
- 스크립트가 새 목표 유형을 만들 수는 없습니다. 콘텐츠 검사가 Skript보다 먼저 돌아서, 모르는 유형은 검사에 걸리기 때문입니다.

```yaml
# content/quests/region.yml
- id: quest/region/lighthouse_bell
  name: 등대 종 울리기
  type: region
  objectives:
    - {key: ring, type: use_item, target: item/tool/bell_hammer, count: 3, text: 등대 종을 세 번 울린다}
```

```
on right click on bell:
    harbor quest signal "use_item" with target "item/tool/bell_hammer" for player

command /퀘스트상태 <text>:
    trigger:
        send "상태: %harbor quest state of player for arg-1%" to player
```

**이벤트**

| 이벤트 | 값 |
|---|---|
| `on harbor quest accept` | `event-player`, `event-string` = 퀘스트 ID |
| `on harbor quest complete` | `event-offlineplayer`, `event-string` = 퀘스트 ID (보상을 다 준 뒤) |

```
on harbor quest complete:
    event-string is "harbor:quest/main/ch1_dark_lighthouse"
    broadcast "&e%event-offlineplayer% 님이 꺼진 등대를 밝혔습니다!"
```

## 낚시 (Harbor-Fishing)

```
on harbor fish catch:            # 또는 on harbor catch
```

| 값 · 식 (이 이벤트 안에서만) | 내용 |
|---|---|
| `event-player` | 낚은 플레이어 |
| `event-string`, `harbor caught fish [id]` | 어종 ID (`harbor:fish/…`) |
| `harbor caught fish name` | 어종 이름 |
| `harbor catch grade` | `common` · `uncommon` · `rare` · `epic` · `legendary` · `mythic` |
| `harbor catch region` | 지역 ID |
| `event-number`, `harbor catch size` | 크기 (cm) |
| `harbor catch quality` | 품질 ★ (1~5) |
| `harbor catch is [a] first catch` · `(isn't\|is not)` | 처음 낚은 어종인지 (조건) |

```
on harbor fish catch:
    harbor catch grade is "legendary"
    broadcast "&6%player% 님이 %harbor caught fish name% (%harbor catch size%cm)를 낚았습니다!"
```

## 도감 · 기록 (Harbor-Collection)

```
[the] harbor [season] collection percent[age] of %players%     # 이번 시즌 도감 %
[the] harbor total collection percent[age] of %players%        # 전체 도감 %
%players% (has|have) registered harbor fish %string%     # 전체 도감 등록 여부
%players% (hasn't|has not|haven't|have not) registered harbor fish %string%
[the] harbor (best|record) size of %players% for [fish] %string%   # 개인 최대어 cm
open harbor collection [of %-offlineplayer%] (to|for) %players%        # 도감 화면 (다른 사람 도감도 볼 수 있음)
```

| 이벤트 | 값 |
|---|---|
| `on harbor collection register` | `event-offlineplayer`, `event-string` = 어종 ID |
| `on harbor record broken` | `event-offlineplayer`, `event-string` = 어종 ID, `event-number` = 크기 cm |

```
on harbor collection register:
    if harbor total collection percent of event-offlineplayer >= 50:
        harbor set flag "skript/collection_half" for event-offlineplayer
```

(`event-offlineplayer`가 접속 중이 아니면 플레이어 목록 인자를 받는 문법은 아무것도 하지 않습니다.)

## 직업 (Harbor-Jobs)

| 이벤트 | 값 |
|---|---|
| `on harbor level up` | `event-offlineplayer`, `event-number` = 새 레벨 |
| `on harbor job advance` | `event-offlineplayer`, `event-number` = 새 단계, `event-string` = 계열 |

## NPC · 컷씬 (Harbor-Npc)

```
play harbor cutscene %string% (to|for) %players%
%players% (is|are) watching [a] harbor cutscene
%players% (isn't|is not|aren't|are not) watching [a] harbor cutscene
open harbor dialog [of npc] %string% (to|for) %players%    # 그 NPC 대화를 연다 (거리 검사는 클릭할 때 함)
```

```
command /프롤로그:
    trigger:
        play harbor cutscene "prologue_drift" for player
```

## 시즌 · 칭호 (Harbor-Season)

```
[the] harbor season week                       # 지금 주차
[the] harbor season name                       # 시즌 표시 이름
%players% (has|have) harbor title %string%
%players% (doesn't|does not|don't|do not) have harbor title %string%
[the] equipped harbor title of %players%       # 장착한 칭호 ID
equip harbor title %string% (for|to|on) %players%   # 가지고 있는 칭호만 장착됨
unequip harbor title (of|for|from) %players%
```

칭호 지급·회수는 관리자용 `harbor admin grant/revoke title …`로만 합니다 ([docs/core-jobs-season.md](docs/core-jobs-season.md)). 보통은 퀘스트·도감·시즌 보상으로 받게 합니다.

## 시세 · 경매 (Harbor-Economy)

```
[the] harbor market (coefficient|rate) of [fish] %string%        # 지금 시세 계수 (1.0 = 기준가)
[the] harbor next market (coefficient|rate) of [fish] %string%   # 다음 시간 예측 (없으면 값 없음)
```

```
on harbor auction sold:            # 또는 on harbor auction sale
```

| 값 · 식 | 내용 |
|---|---|
| `event-number` | 낙찰가 |
| `event-string` | 아이템 ID |
| `harbor auction buyer` · `harbor auction seller` | 산 사람 · 판 사람 (오프라인 플레이어) |

```
every 1 hour:
    if harbor market rate of fish "harbor_mackerel" >= 1.3:
        broadcast "&a지금 고등어 값이 좋습니다!"
```

## 해역 이벤트 (Harbor-World)

```
on harbor sea event start:
on harbor sea event end:
```

| 값 · 식 | 내용 |
|---|---|
| `event-string` | 해역 이벤트 ID |
| `harbor sea event name` | 이름 |
| `harbor sea event region` | 지역 ID (지역 한정 이벤트만) |

```
on harbor sea event start:
    broadcast "&b[해역] %harbor sea event name%이(가) 시작됐습니다!"
```

## 일부러 열지 않은 것

- **진주(유료 재화):** 잔액 읽기·지급·차감·이벤트 모두 열지 않습니다. 결제·환불과 묶인 재화라 스크립트로 바꾸면 정산이 어긋납니다.
- **골드 우편:** 받을 때 원장에 `MAIL_GOLD`로 기록되어 스크립트 지급 감사(`SCRIPT_*`)에서 빠집니다. 오프라인 플레이어에게도 `harbor give N gold`를 쓰세요.
- **다른 기능의 수치 보정 지우기:** 스크립트가 거는 보정의 출처는 항상 `skript:` 아래라서, 스킬·이벤트 보정은 지울 수 없습니다.

모듈 API가 아직 없어서 열지 못한 기능은 각 문서 끝의 "없는 것" 절에 있습니다.

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

새 문법은 두 가지 방법으로 추가합니다.

- **간단한 식·조건·효과:** `bridge/<모듈>Syntax`에서 `lambda/Registrar`로 등록합니다. 예: `r.number(Spec.of(...), (event, exprs) -> ..., "패턴")`. 새 클래스를 만들 필요가 없습니다.
- **복잡한 것:** 재화 changer처럼 복잡한 문법만 `ExprBalance`처럼 클래스를 따로 만들어 `SkriptSyntax.register`에 등록합니다.

지킬 것:

- **모듈 클래스:** 게임 모듈 클래스는 그 모듈의 `bridge/*Syntax` 안에서만 씁니다. `SkriptSyntax`는 모듈이 켜져 있을 때만 그 bridge를 불러옵니다.
- **새 모듈 연결:** `paper-plugin.yml`에 선택 의존(`required: false`, `join-classpath: true`)을 넣고, `build.gradle.kts`에 compileOnly 의존을 추가합니다.
- **데이터 접근:** 재화·아이템·플레이어 데이터는 반드시 `Harbor.api()`와 모듈 api 서비스로 다룹니다. 스크립트는 메인 스레드에서 돌므로 DB에 직접 접근하지 않습니다.
