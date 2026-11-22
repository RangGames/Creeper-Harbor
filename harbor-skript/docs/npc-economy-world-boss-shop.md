# Harbor-Skript: NPC · 경제 · 월드 · 보스 · 상점 문법

[README](../README.md)의 기본 문법에 더해, 아래 모듈이 켜져 있을 때 등록되는 문법입니다. 각 절 제목의 **(모듈 이름)** 이 꺼져 있으면 그 절의 문법은 "알 수 없는 문법"이 됩니다.

ID는 README "ID 쓰는 법"과 같습니다. `harbor:`와 종류 접두사(`npc/` · `region/` · `boss/` · `cosmetic/` · `shop/` · `station/` · `cutscene/`)는 빼도 됩니다. 식이 돌려주는 ID는 항상 `harbor:npc/bawi`처럼 전체 형식입니다.

## 공통 규칙

- **메인 스레드:** 모든 문법은 메인 스레드에서 메모리 값만 읽거나 Harbor API를 부릅니다. DB가 필요한 쓰기(호감도·평판·치장 지급 등)는 Harbor가 비동기로 저장하므로 바로 다음 줄에서는 이전 값일 수 있습니다.
- **DB를 읽는 식** (경매 매물, 안 받은 우편 수, 보스 기록): 처음 읽으면 **값이 없고**, 그 사이 백그라운드에서 불러옵니다. 이후에는 마지막으로 불러온 값을 주고, `balance.yml skript.cache_seconds`(기본 30초)가 지나면 다음 읽기 때 다시 불러옵니다. 실시간 값이 아니라는 점을 문구에 반영하세요.
- **온라인 기준:** 플레이어 값(호감도, 배, 패스, 치장 등)은 **이 서버에 접속한 플레이어만** 있습니다. 하우징 부지는 불러온 부지(주인 접속 중·방문 중)만 보입니다.
- **관리자 효과:** 이름에 `harbor admin`이 들어간 효과는 정상 진행 경로를 건너뛰는 운영용입니다. 명령에 권한을 꼭 거세요.
- **진주(유료 재화)는 없습니다.** 잔액 읽기·지급·사용·진주 변동 이벤트 모두 스크립트에 열지 않습니다.

## NPC · 대화 · 컷씬 (Harbor-Npc)

### 평판

```
[the] harbor reputation tier of %players%              %players%'[s] harbor reputation tier
[the] harbor best reputation tier of %players%          # 시즌을 넘어 유지되는 최고 등급
[the] harbor reputation tier name of %players%          # 지금 등급 이름
[the] harbor next reputation tier points of %players%   # 다음 등급 누적 점수 (최고 등급이면 없음)
harbor give %number% reputation to %players%            # 출처 OTHER, 일일 상한·보정 적용
harbor take %number% reputation from %players%
```

평판 점수는 README의 `harbor reputation of %players%`(Core)로 읽습니다.

### 호감도

```
[the] harbor affinity [level] of %players% with [npc] %string%       # 단계 (0부터)
[the] harbor affinity points of %players% with [npc] %string%        # 점수. add · remove · set 가능
[the] harbor affinity level name of %players% with [npc] %string%
[the] harbor affinity discount of %players% with [npc] %string%      # 상점 할인 %
%players% (has|have) harbor affinity unlock %string% with [npc] %string%
%players% (doesn't|does not|don't|do not) have harbor affinity unlock %string% with [npc] %string%
```

- 점수 변경은 `AffinityService`(출처 `OTHER`)를 거칩니다. 0 아래로 내려가지 않고, `on harbor affinity change` · `on harbor affinity level up`이 발생합니다.
- `set`은 지금 점수와의 차이만큼 더하거나 뺍니다.

```
command /바위선물:
    trigger:
        add 20 to harbor affinity points of player with npc "bawi"
```

### NPC 정보

```
[all] [the] harbor npcs                       [all] [the] harbor npc ids
[the] harbor npc name of %string%              # 기본 이름
[the] harbor npc display name of %string% for %player%   # 그 플레이어에게 보이는 이름 (MiniMessage)
[the] harbor npc role of %string%              # story · job · collect · shop · region · wanderer
[the] harbor npc shop of %string%              # 상점 ID (없으면 값 없음)
[the] harbor npc dialog [tree] of %string%     # 대화 트리 ID
[the] harbor npc location of %string%          # 지금 서 있는 위치 (이 서버에 없으면 값 없음)
harbor npc %string% is spawned                 harbor npc %string% (isn't|is not) spawned
harbor npc %string% is talking                 # 누군가와 대화 세션이 열려 있음
[the] nearest harbor npc (to|of) %players% within %number% [blocks]
%players% (is|are) within %number% [blocks] of harbor npc %string%
[the] harbor npc busy reason of %players%      # NPC 클릭이 막힌 이유 (미니게임·보스전 등)
```

**떠돌이 NPC**

```
[the] harbor wanderer (candidate|spot) of %string%
[the] harbor wanderer location of %string%
[the] harbor wanderer next move (seconds|time) of %string%   # 다음 이동까지 초
harbor admin force wanderer %string% to [candidate] %string%  # 강제 이동 (서버 플래그)
harbor admin reset wanderer %string%                          # 강제 이동 해제
```

### 대화

```
open harbor dialog [of npc] %string% (to|for) %players%
open harbor dialog [of npc] %string% at node %string% (to|for) %players%
close harbor dialog (of|for) %players%
%players% (is|are) in [a] harbor dialog
[the] harbor dialog npc of %players%          [the] harbor dialog node of %players%
[the] harbor dialog tree of %players%
make harbor npc %string% say %string% to %players% [(in|with) style %-string%]
make harbor npc %string% present %strings% to %players% [with choices %-strings%] [(tagged|with tag) %-string%]
```

- `say`: 세션 없는 한 줄 대사. 스타일은 `dialog` · `hologram` · `title` · `chat` · `actionbar` (생략하면 NPC 기본).
- `present`: 대화 트리 없이 대사 여러 쪽과 선택지를 보입니다. 고르면 `on harbor npc speech choice`가 **정확히 한 번** 옵니다 (닫거나 멀어지면 -1). 태그로 어떤 질문인지 구분하세요.

```
command /항구퀴즈:
    trigger:
        make harbor npc "bawi" present "<white>등대는 몇 층일까?" to player with choices "3층" and "5층" tagged "quiz_lighthouse"

on harbor npc speech choice:
    event-string is "quiz_lighthouse"
    if event-number is 1:
        harbor give 5 reputation to player
```

### 스크립트로 대화 액션 · 조건 만들기

대화 YAML에서 `skript:action` · `skript:check` 키를 쓰면 스크립트 이벤트가 발생합니다. `value`가 스크립트 ID이고, 다른 인자는 `harbor dialog argument`로 읽습니다.

```yaml
# content/dialogs/bawi.yml (선택지 일부)
options:
  - label: npc.dialog.option.give_bait      # messages_ko.yml 키
    conditions: ["skript:check": has_bait]
    actions: [{type: skript:action, value: give_bait, amount: "3"}]
```

| 이벤트 | 값 · 식 |
|---|---|
| `on harbor dialog action` | `event-player`, `event-string` = 액션 ID, `harbor dialog argument %string%`, `harbor dialog current npc` · `current node` |
| `on harbor dialog condition` | 위와 같음. 기본은 **불통과** |

결과 정하기 (이벤트 안에서만 효과가 있음):

```
harbor dialog result continue          # 기본값. 다음 액션으로
harbor dialog result close
harbor dialog result handoff           # 다른 화면을 열었으니 대화만 끝냄
harbor dialog result go[ ]to node %string%
harbor dialog result go[ ]to page %number%
harbor dialog result fail [with message %-string%] [(and|then) go[ ]to node %-string%]   # 메시지는 messages_ko.yml 키
harbor dialog check pass[es]           harbor dialog check fail[s]
```

```
on harbor dialog condition:
    event-string is "has_bait"
    player has 3 of harbor item "item/bait/flour_paste"
    harbor dialog check pass

on harbor dialog action:
    event-string is "give_bait"
    remove 3 of harbor item "item/bait/flour_paste" from player
    harbor dialog result go to node "thanks"
```

- 결과는 이벤트 처리 중에 바로 읽습니다. `wait` 뒤에 정한 결과는 반영되지 않습니다.
- 조건은 클릭 때마다 평가되니 가볍게 쓰세요.
- 콘텐츠를 처음 읽을 때는 Harbor-Skript가 아직 켜지기 전이라 서버 로그에 "등록되지 않은 대화 액션" 경고가 한 번 날 수 있습니다. 실행 때는 정상 동작합니다.
- 아이템·재화를 줄 때는 README 규칙(`harbor item`, `harbor gold`)을 따르세요. 반복 대화로 중복 지급되지 않게 플래그로 막으세요.

### 컷씬

```
play harbor cutscene %string% (to|for) %players%     # 여러 명이면 같은 틱에 함께 시작
stop harbor cutscene (of|for) %players%
%players% (is|are) watching [a] harbor cutscene
[the] harbor cutscene of %players%                   # 보고 있는 컷씬 ID
%players% (has|have) seen harbor cutscene %string%
```

### NPC 이벤트

| 이벤트 | 값 · 식 |
|---|---|
| `on harbor npc (interact\|click)` | `event-player`, `event-string` = NPC ID. `cancel event`로 대화를 막음. `harbor npc click is [a] right click` · `left click` |
| `on harbor dialog option select[ed]` | `event-player`, `event-string` = NPC ID, `event-number` = 선택지 번호(0부터), `harbor event dialog node` · `tree` |
| `on harbor dialog end` | `event-player`, `event-string` = NPC ID, `harbor dialog end reason`(closed · handoff · replaced · expired · quit), `harbor last dialog node` |
| `on harbor [npc] gift` | `event-player`, `event-string` = NPC ID, `event-number` = 호감도 변화, `harbor gift item`, `harbor gift reaction`(love · like · normal · dislike) |
| `on harbor affinity change` | `event-offlineplayer`, `event-string` = NPC ID, `event-number` = 변화량, `harbor affinity source`, `harbor event old value` · `new value` |
| `on harbor affinity level up` | `event-offlineplayer`, `event-string` = NPC ID, `event-number` = 새 단계 |
| `on harbor reputation change` | `event-offlineplayer`, `event-number` = 변화량, `event-string` = 출처, `harbor event old value` · `new value` |
| `on harbor reputation tier change` | `event-offlineplayer`, `event-number` = 새 등급, `harbor event best tier` |
| `on harbor cutscene start` | `event-player`, `event-string` = 컷씬 ID |
| `on harbor cutscene end` | `event-player`, `event-string` = 컷씬 ID, `harbor cutscene result`(finished · skipped · cancelled · busy), `harbor cutscene (was\|is) completed` |
| `on harbor wanderer move[d]` | `event-string` = NPC ID, `event-location`, `harbor event wanderer candidate`, `harbor wanderer is visible` |
| `on harbor npc speech choice` | `event-player`, `event-number` = 고른 번호(-1 = 닫음), `event-string` = 태그 |

`harbor event npc`는 NPC가 있는 모든 이벤트에서 NPC ID를 줍니다.

## 경제 (Harbor-Economy)

### 시세 · 판매가 · 수리비

```
[the] harbor market (coefficient|rate) of [fish] %string%
[the] harbor next market (coefficient|rate) of [fish] %string%
[the] harbor sell (value|price) of %itemstacks% for %player%     # 그 플레이어의 판매가 보너스 포함 (× 개수)
[the] harbor base (value|price) of %itemstacks%                  # 개인 보너스 없는 기준가
[the] harbor repair cost of [price] %number% for %player%         # 장비 가격 기준 수리비 견적
[the] harbor broken repair cost of [price] %number% for %player%  # 내구도 0일 때
```

물고기가 아니거나 팔 수 없는 아이템(신화·방생 전용)은 값이 없습니다. **판매 자체는 스크립트로 하지 않습니다** (판매 API가 없음). 플레이어가 판매 화면에서 팝니다.

### 화면 열기

```
open harbor shop %string% (to|for) %players%                    # shop/…
open harbor craft[ing] station %string% (to|for) %players%      # station/…
open harbor craft[ing] (kind|menu) %string% (to|for) %players%  # workshop · cooking · shipyard
```

### 경매 · 우편 (DB, 결과가 늦게 옴)

```
[all] [the] harbor auction listings [in category %-string%]      # 매물 번호 목록 (최신순, balance.yml skript.auction_limit개까지)
[the] harbor auction listing item [id] of %number%
[the] harbor auction listing item name of %number%
[the] harbor auction listing category of %number%
[the] harbor auction listing seller name of %number%
[the] harbor auction listing seller of %number%                  # 오프라인 플레이어
[the] harbor auction listing price of %number%
[the] harbor auction listing amount of %number%
[the] harbor auction listing (seconds left|remaining seconds) of %number%
[the] harbor lowest auction price of %string%                   # 그 아이템(어종) ID의 최저가
[the] (number|amount) of harbor auction listings of %offlineplayers%
[the] (number|amount) of unclaimed harbor mail[s] of %offlineplayers%
```

- 매물 정보(`… of %number%`)는 전체 목록(`harbor auction listings`, 분류 없음)을 한 번 읽은 뒤에 나옵니다.
- 경매 등록·구매는 플레이어의 `/경매` 화면에서만 합니다.
- 아이템 우편은 README의 `send harbor mail`을 쓰세요. 골드 우편은 열지 않았습니다 (스크립트 재화는 `harbor gold` = 원장 `SCRIPT_*`로만).

```
every 5 minutes:
    set {_low} to harbor lowest auction price of "fish/harbor_mackerel"
    if {_low} is set:
        broadcast "&e고등어 경매 최저가: %{_low}%G"
```

### 경제 이벤트

| 이벤트 | 값 · 식 |
|---|---|
| `on harbor auction (sold\|sale)` | `event-number` = 낙찰가, `event-string` = 아이템 ID, `harbor auction buyer` · `seller`, `harbor auction fee`, `harbor event auction listing id`, `harbor sale is held` |
| `on harbor auction list[ed]` | `event-offlineplayer` = 판매자, `event-number` = 가격, `event-string` = 아이템 ID, `harbor auction listed amount`, `harbor auction fee` |
| `on harbor fish (sold\|sale)` | `event-offlineplayer`, `event-number` = 합계 골드, `harbor sold fish`(어종 ID 목록), `number of harbor sold fish` |
| `on harbor [item] craft[ed]` | `event-offlineplayer`, `event-string` = 결과 아이템 ID, `event-number` = 개수, `harbor craft recipe`, `harbor craft gold` |
| `on harbor shop purchase` | `event-offlineplayer`, `event-string` = 상점 항목 키, `event-number` = 가격, `harbor purchase shop` · `item` · `amount` |
| `on harbor market update[d]` | (값 없음) 매시 시세표 갱신 뒤 |
| `on harbor trade flag[ged]` | `event-number` = 점수, `event-string` = 참조 ID, `harbor trade buyer` · `seller` · `reasons`, `harbor trade is held` |

## 월드 (Harbor-World)

### 지역

```
[all] [the] harbor regions                       [all] [the] harbor region ids
[the] harbor region at %location%
[the] harbor areas at %location%                  # 세부 구역 ID 목록
[the] [current] harbor region of %players%        %players%'[s] [current] harbor region
[the] harbor region name of %string%              [the] harbor region world of %string%
[the] harbor region server of %string%            [the] harbor region areas of %string%
[the] harbor region order of %string%             [the] harbor region recommended level of %string%
[the] harbor region (occupancy|player count) of %string%     # 이 서버 인원
[the] visited harbor regions of %player%          # 이번 시즌 방문한 지역
%players% (has|have) visited harbor region %string%
%players% can enter harbor region %string%        %players% (can't|cannot|can not) enter harbor region %string%
[the] missing harbor region requirements of %player% for %string%   # "level:…:18/20" 형식 목록
[the] highest unlocked harbor region of %players%
harbor admin travel %players% to region %string%  # 입장 조건 무시, 다른 서버면 서버 이동
```

지역 **해금 자체**는 레벨·퀘스트·플래그 조건으로 정해집니다. 조건 중 플래그는 README의 `harbor set flag`로 줄 수 있습니다.

### 배

```
[the] harbor ship ids of %player%                [the] harbor ship types of %player%
[the] harbor (afloat|boarded) ship (type|name|state) of %players%
[the] harbor (afloat|boarded) ship (id|durability|max durability) of %players%
[the] harbor (afloat|boarded) ship (speed|cargo|hull) level of %players%
[the] harbor ship speed multiplier of %players%
%players% (is|are) aboard [a] harbor ship
%players% (has|have) [a] harbor ship afloat
```

`afloat` = 그 플레이어가 띄운 배, `boarded` = 지금 타고 있는 배(주인 무관). 상태는 `stored` · `afloat` · `wrecked`.
(패턴은 실제로 `harbor afloat ship type of …`처럼 한 단어씩 따로 등록돼 있습니다.)

### 하우징

```
%location% is in [a] harbor housing world
[the] harbor plot (id|size|likes) (at %location%|of %offlineplayer%)
[the] harbor plot (theme|visit policy) (at %location%|of %offlineplayer%)
[the] harbor plot members (at %location%|of %offlineplayer%)     # 공동 건축자
[the] harbor plot spawn (at %location%|of %offlineplayer%)       # 부지 시작 위치
[the] harbor plot owner at %location%
%offlineplayer% is [a] member of [the] harbor plot at %location%
%players% can (visit|build|edit display|manage) [the] harbor plot at %location%
```

`of %offlineplayer%`는 불러온 부지만 됩니다 (주인이 접속 중이거나 누가 방문 중). 방문 정책은 `public` · `members` · `private`.

### 보물 지도 · 해도 · 물고기 떼

```
[a] [new] harbor treasure map (of|in|for) region %string% for %player% [with tier %-string%]   # 아이템
%itemstacks% (is|are) [a] harbor treasure map[s]
[the] harbor chart percent[age] of %players% (in|for) region %string%
[the] harbor chart [reward] steps of %players% (in|for) region %string%
[the] harbor chart (item|map) (of|for) region %string% for %player%
[the] harbor fishing spot near %location% within %number% [blocks]       # 자리 ID (공개 자리만)
[all] [the] harbor fishing spot locations (in|of) region %string%
```

보물 지도는 지도마다 고유 번호가 있어 한 번만 보상을 받습니다. 그래도 반복 지급 명령은 플래그로 막으세요.

### 해역 이벤트

```
[all] [the] active harbor sea events [in region %-string%]
[all] [the] upcoming harbor sea events
%location% is in [a] harbor storm
```

이벤트 안에서: `harbor sea event name` · `region` · `type`(storm · ghost_ship · migration · tournament · special) · `(seconds left|remaining seconds)`.

### 월드 이벤트

| 이벤트 | 값 · 식 |
|---|---|
| `on harbor sea event warn[ing]` · `start` · `end` | `event-string` = 해역 이벤트 ID |
| `on harbor region enter` | `event-player`, `event-string` = 지역 ID, `harbor previous region` |
| `on harbor region leave` | `event-player`, `event-string` = 지역 ID, `harbor next region` |
| `on harbor region first visit` | `event-player`, `event-string` = 지역 ID |
| `on harbor region block[ed]` | `event-player`, `event-string` = 지역 ID, `harbor blocked requirements` |
| `on harbor chart progress` | `event-player`, `event-string` = 지역 ID, `event-number` = 완성도 %, `harbor chart reward step` |
| `on harbor fishing spot move[d]` | `event-string` = 자리 ID, `event-location` |
| `on harbor plot like[d]` | `event-player` = 누른 사람, `event-offlineplayer` = 주인, `event-number` = 좋아요 수 |
| `on harbor plot load[ed]` · `unload[ed]` | `event-offlineplayer` = 주인, `event-number` = 부지 ID |
| `on harbor (display anchor\|aquarium tank) change[d]` | `event-offlineplayer` = 주인, `event-number` = 슬롯, `event-string` = 장식 ID, `harbor display anchor was added` · `removed` |
| `on harbor ship summon` | `event-player`, `event-string` = 배 종류, `event-number` = 배 번호, `harbor event ship name`. `cancel event`로 막음 |
| `on harbor ship wreck[ed]` | `event-player` = 주인, `event-string` · `event-number` 위와 같음 |
| `on harbor treasure claim[ed]` | `event-player`, `event-string` = 지역 ID, `event-number` = 골드, `harbor treasure tier` · `site`, `harbor treasure (has\|gave) [a] hint` |

```
on harbor region first visit:
    event-string is "harbor:region/frost_fjord"
    show harbor title "<aqua>서리 피오르" with subtitle "<gray>얼음 사이로 숨이 하얗다" to player
```

## 보스 (Harbor-Boss)

### 보스 · 진행 중인 전투

```
[all] [the] harbor bosses                 [all] [the] harbor boss ids
[the] harbor boss name of %string%         [the] harbor boss type of %string%   # intro · sea · dimension · raid · world
%players% (is|are) in [a] harbor boss fight
[the] harbor boss of %players%            %players%'[s] harbor boss            # 보스 ID
[the] harbor boss (encounter id|phase|server) of %players%
[the] harbor boss (hp|hull|max hull|slot|elapsed seconds) of %players%         # hp는 남은 % (100 → 0)
[the] harbor boss role of %players%       # anchor · harpooner · captain · mechanic · quartermaster
[the] harbor boss (participants|members) of %player%
[the] harbor boss leader of %player%
[all] [the] active harbor boss encounters
harbor admin (start|force start) boss %string% (with|for) [leader] %player%    # 소환 미끼 없이 강제 시작
harbor admin end [the] boss fight of %players% as (cleared|failed|aborted)
```

단계(phase)는 `preparing` · `intro` · `hook` · `counter` · `final_tug` · `cleared` · `failed` · `aborted`입니다. 강제 시작이 실패하면 이유가 스크립트 경고로 찍힙니다.

### 월드 보스

```
[the] harbor world boss [id]                [the] harbor world boss (phase|state)
[the] harbor world boss (percent|gauge)     [the] harbor world boss stage      # 0~4
[the] harbor world boss server
[the] (number|amount) of harbor world boss participants
[the] harbor world boss is running [on this server]
harbor admin start world boss %string%
```

### 기록 (DB, 결과가 늦게 옴)

```
[the] harbor boss record (time|seconds) of %string% [(at|with) rank %-number%] [in season %-number%]
[the] harbor boss record (members|holders) of %string% [(at|with) rank %-number%] [in season %-number%]
[the] (number|amount) of harbor boss records of %string% [in season %-number%]
```

순위를 빼면 1위, 시즌을 빼면 전체 시즌 기록입니다 (시즌마다 10위까지 보관).

### 파티

```
%players% (is|are) in [a] harbor party
[the] harbor party members of %offlineplayer%      # 파티가 없으면 자기 자신
[the] harbor party leader of %offlineplayer%
[the] harbor party role of %players%
%offlineplayer% and %offlineplayer% are in the same harbor party
```

파티 만들기·초대·탈퇴는 `/파티` 명령으로만 합니다 (API 없음).

### 보스 이벤트

| 이벤트 | 값 · 식 |
|---|---|
| `on harbor boss (start\|encounter start)` | `event-string` = 보스 ID, `event-offlineplayer` = 파티장, `harbor boss start (is\|was) forced`. `cancel event`로 막음 |
| `on harbor boss (end\|encounter end)` | `event-string` = 보스 ID, `event-number` = 걸린 초, `event-offlineplayer` = 파티장, `harbor boss result`, `harbor boss (was\|is) cleared`, `harbor boss (was\|is) a story fight`, `harbor boss fish` |
| `on harbor boss phase change` | `event-string` = 새 단계, `harbor boss old phase`, `harbor boss phase cause` |
| `on harbor world boss progress` | `event-number` = 단계(1~3), `event-string` = 보스 ID. 실행 서버에서만 |

시작·끝·단계 이벤트에서 `harbor event boss`, `harbor event boss encounter id`, `harbor event boss (participants|members)`를 씁니다. 끝 이벤트(보상 지급 전)에서는 참가자별 기여도를 읽습니다.

```
[the] harbor boss contribution (score|rank|damage|tension|repair|evasion) of %offlineplayer%
[the] harbor boss contribution role of %offlineplayer%
%offlineplayer% (is|was) [a] top harbor boss contributor          # 상위 20%
%offlineplayer% (is|was) eligible for harbor boss rewards         # 최소 참여 충족
```

```
on harbor boss end:
    harbor boss was cleared
    loop harbor event boss participants:
        if harbor boss contribution rank of loop-value is 1:
            broadcast "&6%loop-value% 님이 %harbor event boss% 토벌 MVP!"
```

## 상점 · 치장 · 시즌 패스 (Harbor-Shop)

### 치장

```
[the] [owned] harbor cosmetics of %player%
%players% (own|owns) harbor cosmetic %string%
[the] equipped harbor cosmetic of %players% in [slot] %string%
equip harbor cosmetic %string% (on|for|to) %players%          # 가진 것만 장착됨
unequip harbor cosmetic [slot] %string% (of|for|from) %players%
[the] harbor [rod] skin of %itemstacks%                        # 낚싯대에 입힌 스킨 ID
harbor admin grant cosmetic %string% to %offlineplayers%       # 지급 (출처 ADMIN, 참조 ID skript:…)
harbor admin revoke cosmetic %string% from %offlineplayers%
[the] harbor donor tier of %players%                           # none · harbor · fleet
```

슬롯: `rod_skin` · `line_fx` · `catch_fx` · `boat_skin` · `pet` · `title_frame` · `name_color` · `chat_icon` · `emote` · `outfit` · `aquarium_theme` · `housing_deco` (밑줄 대신 띄어 써도 됨).

치장은 계정 기록이라 시즌이 바뀌어도 남습니다. 지급은 이벤트 보상처럼 꼭 필요한 곳에만 쓰고, 운영 기록은 `cosmetics_owned.ref_id`의 `skript:` 접두사로 찾습니다.

### 시즌 패스

```
[the] harbor [season] pass level of %players%
[the] harbor [season] pass (exp|experience) of %players%
[the] harbor [season] pass premium (tier|level) of %players%     # 0 무료 · 1 프리미엄 · 2 프리미엄+
[all] [the] harbor [season] pass (exp|experience) sources
harbor admin give %number% [season] pass (exp|experience) unit[s] (from|of) source %string% to %players%
```

경험치는 `shop/pass_s<n>.yml exp_sources`의 그 출처 값 × 단위 수입니다. 패스 파일에 없는 출처면 아무것도 주지 않습니다.

### 상점 이벤트

| 이벤트 | 값 · 식 |
|---|---|
| `on harbor cosmetic equip` | `event-player`, `event-string` = 새 치장 ID(해제면 없음), `harbor cosmetic slot`, `harbor previous cosmetic` |
| `on harbor cosmetic grant[ed]` | `event-offlineplayer`, `event-string` = 치장 ID, `harbor cosmetic grant source` |
| `on harbor donor tier change` | `event-offlineplayer`, `event-string` = 새 등급 |
| `on harbor festival progress` | `event-string` = 축제 ID, `event-number` = 달성률 %, `harbor festival goal (is\|was) reached` |
| `on harbor [season] pass level up` | `event-offlineplayer`, `event-number` = 새 레벨, `harbor pass previous level` |

축제 이벤트는 금액이 아니라 달성률만 줍니다. 진주 변동 이벤트(`PearlBalanceChangedEvent`)는 등록하지 않습니다.

## 설정 (balance.yml `skript`)

| 키 | 기본 | 내용 |
|---|---|---|
| `cache_seconds` | 30 | DB를 읽는 식의 값 유지 시간 (제안값) |
| `auction_limit` | 100 | `harbor auction listings`가 불러오는 최대 매물 수 (제안값) |

## 없는 것 (공개 API가 없어 열지 못함)

| 원하는 것 | 막힌 이유 |
|---|---|
| NPC 소환 · 제거 · 순간이동 · 바라보기 | `NpcService`가 조회와 바쁨 검사만 있음. NPC는 `npcs.yml`·떠돌이 일정으로만 움직임 |
| 물고기 대신 팔기 · 상점 대신 사기 · 경매 등록·구매 | Economy api에 판매·구매 함수가 없음 (`PriceService`는 견적만, `ShopService.charge`는 다른 모듈용 결제) |
| 대신 제작하기 · 수리비 결제 | `CraftService`는 화면 열기만, `RepairService.pay`는 장비·배 갱신 SQL을 같이 넘겨야 함 |
| 지역 해금 관리 | 해금 = 레벨·퀘스트·플래그 조건이라 별도 API 없음 (플래그 조건은 `harbor set flag`) |
| 배 띄우기 · 회수 · 내구도 변경 | `ShipService`가 조회 전용 |
| 하우징 방문 이동 · 권한(공동 건축자) 변경 | `HousingService`가 조회·권한 검사만 있음 (`harbor plot spawn`으로 위치는 얻을 수 있음) |
| 비행선 · 탈것 | World api에 비행선 서비스가 없음 |
| 보스 주간 입장 횟수 | Boss api에 주간 제한 조회가 없음 |
| 파티 만들기 · 초대 · 탈퇴 | Core `PartyService`가 조회 전용 |
| 진주 잔액·지급·사용, 패스 레벨 구매 여부 | README 규칙: 유료 재화는 스크립트에서 다루지 않음 |

## 실서버 확인 필요

- `skript:action` · `skript:check` 키의 첫 콘텐츠 로드 때 경고 한 번, 리로드 뒤 정상 동작
- `present` 선택 결과가 정확히 한 번 오는지 (닫기·멀어짐 -1)
- DB 식의 첫 읽기 "값 없음" 뒤 다음 읽기에서 값이 채워지는지
- `harbor afloat ship …` 같은 반복 등록 패턴과 Skript 기본 문법(`amount of …`)의 해석 충돌 여부
