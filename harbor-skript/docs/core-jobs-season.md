# Harbor-Skript 문법: Core · 직업 · 시즌

README의 기본 문법(골드·물비늘, 아이템, 문구, 서버 이동, 플래그, 화면·우편)에 더해 Core 공개 API, Harbor-Jobs, Harbor-Season을 스크립트에서 다루는 문법입니다.

- **(Core)** 표시가 없는 절은 항상 등록됩니다. **(Harbor-Jobs)** · **(Harbor-Season)** 절은 그 모듈이 켜져 있을 때만 등록됩니다.
- 모든 문법은 메인 스레드에서 Harbor API를 부릅니다. DB·Redis를 거치는 일은 Harbor가 비동기로 처리합니다.
- 재화는 언제나 원장(CurrencyService)을 거칩니다. 잔액 열을 직접 바꾸는 문법은 없습니다.

## 결과를 기다리는 문법 (`... in %objects%`)

DB를 읽거나 플레이어의 선택을 기다려야 하는 문법은 **결과가 올 때까지 그 아래 줄을 멈췄다가** 결과를 변수에 넣고 이어서 실행합니다 (Skript `wait`과 같은 방식). 서버는 멈추지 않습니다.

```
harbor load gold rank of player into {_rank}
send "내 골드 순위: %{_rank}%" to player      # 결과가 온 뒤에 실행됨
```

- 결과를 넣는 자리는 **변수만** 됩니다 (`{_x}`, `{x::*}`). 목록 결과는 `{_x::*}`에 넣으세요.
- 결과가 없거나 실패하면 그 변수는 지워집니다 (`is not set`).
- `[and store the result in %-objects%]`처럼 결과 자리가 선택인 문법은, 쓰지 않으면 기다리지 않고 바로 다음 줄로 넘어갑니다.
- 기다리는 동안 이벤트는 이미 끝난 것으로 취급됩니다. 그 뒤에서 `cancel event`는 효과가 없습니다 (Skript `wait`과 같음).
- 대화창을 ESC로 닫는 등 결과가 끝내 오지 않으면 **그 아래 줄은 실행되지 않습니다.**

## 플레이어 정보 (Core)

```
[the] harbor exp[erience] of %players%          %players%'[s] harbor exp[erience]      # 지금 레벨 안의 경험치
[the] harbor play[ ]time of %players%           %players%'[s] harbor play[ ]time        # 총 플레이 시간 (timespan)
[the] harbor first join [date] of %players%     %players%'[s] harbor first join [date]  # 첫 접속 날짜 (date)

%players% (is|are) harbor loaded                # Harbor 데이터가 로드됐는지
%players% (isn't|is not|aren't|are not) harbor loaded
%players% (is|are) harbor frozen                # 서버 이동 스냅샷을 이미 떴는지 (이때 바꾼 것은 저장되지 않음)
%players% (isn't|is not|aren't|are not) harbor frozen

harbor save %players% now                       # 바뀐 데이터를 지금 DB에 씀
harbor save %players% soon                      # 잠시 뒤(balance.yml inventory.commit_save_delay_ticks) 저장
harbor find [player] [named] %string% and (store|save) [it] in %objects%   # 이름으로 플레이어 찾기 (다른 서버·오프라인 포함)

harbor admin set reputation of %players% to %number%                   # 관리자용
harbor admin (add|remove) %number% reputation (to|from) %players%      # 관리자용
```

- `harbor frozen`이 참이면 아이템을 주거나 빼지 마세요. 그 변경은 이동 후 서버에 반영되지 않습니다.
- `harbor save … soon`은 즉시 처리한 거래(재화·우편) 뒤에 인벤토리를 맞춰 저장할 때 씁니다.
- 평판은 원래 NPC·퀘스트 보상으로 바뀝니다. `harbor admin …` 평판 문법은 운영용입니다.

```
command /찾기 <text>:
    trigger:
        harbor find player arg-1 and store it in {_p}
        if {_p} is not set:
            send "그런 플레이어가 없습니다" to player
            stop
        send "%{_p}%의 UUID: %uuid of {_p}%" to player
```

## 수치 보정 · 스탯 (Core)

스킬·버프와 같은 보정 파이프라인(`ADD 합 → MULTIPLY 곱 → balance.yml stats.<키>.min/max`)에 스크립트 보정을 더합니다.

```
harbor add %number% to stat %string% of %players% (from|with) source %string% [for %-timespan%]
harbor multiply stat %string% of %players% by %number% (from|with) source %string% [for %-timespan%]
harbor add %number% to global stat %string% (from|with) source %string% [for %-timespan%]
harbor multiply global stat %string% by %number% (from|with) source %string% [for %-timespan%]
harbor remove modifiers (from|with) source %string% (from|of) %players%
harbor remove global modifiers (from|with) source %string%

[the] harbor stat %string% of %players%                       # balance.yml 기본값에 보정을 적용한 값
[the] harbor stat %string% of %players% with base %number%    # 기본값을 직접 줄 때
```

- 스탯 키는 `rare_weight` · `bite_wait` · `sell_price` · `reel_speed` · `line_strength` · `exp_gain` 등 소문자·숫자·밑줄입니다.
- multiply 값은 배수입니다 (1.1 = +10%). 0보다 작으면 무시합니다.
- **출처는 항상 `skript:`가 앞에 붙습니다.** `"halloween"`으로 쓰면 실제 출처는 `skript:halloween`입니다. 그래서 스크립트가 스킬·해역 이벤트 보정을 지울 수는 없습니다.
- 같은 출처로 다시 더하면 보정이 하나 더 쌓입니다. 바꾸려면 먼저 지우고 더하세요.
- `for`를 주지 않으면 지울 때까지 유지됩니다. 보정은 서버 메모리에만 있고, 서버를 옮기거나 다시 켜면 사라집니다.

```
command /행운물약:
    trigger:
        harbor remove modifiers from source "luck_potion" of player
        harbor multiply stat "rare_weight" of player by 1.2 with source "luck_potion" for 10 minutes
        send "희귀 확률 %harbor stat "rare_weight" of player%" to player
```

## 플래그 (나머지, Core)

README의 플래그 문법에 더해:

```
harbor set seasonal server flag %string% [to %-string%]      # 시즌이 바뀌면 지워지는 서버 플래그
on harbor [player] flag change:                               # 플레이어 플래그가 DB에 저장된 뒤
on harbor server flag change:                                 # 서버 플래그가 바뀐 뒤 (다른 서버 변경 포함)
[the] harbor changed flag value                               # 두 이벤트 안에서 새 값 (꺼졌으면 값 없음)
```

| 이벤트 | 값 |
|---|---|
| `on harbor flag change` | `event-offlineplayer` · `event-player`(접속 중일 때), `event-string` = 플래그 이름 |
| `on harbor server flag change` | `event-string` = 플래그 이름 |

```
on harbor server flag change:
    event-string is "event/storm_week"
    if harbor changed flag value is set:
        broadcast "&b폭풍 주간이 시작됐습니다!"
```

## 재화 (나머지, Core)

README의 `harbor gold of %players%`는 이 서버에 접속한 플레이어만 다룹니다. 아래 문법은 **오프라인·다른 서버 플레이어**도 됩니다.

```
harbor (give|grant) %number% (gold|mulbineul) to %offlineplayers% [and (store|save) [the] result[s] in %-objects%]
harbor take %number% (gold|mulbineul) from %offlineplayers% [and (store|save) [the] result[s] in %-objects%]
harbor transfer %number% (gold|mulbineul) from %offlineplayer% to %offlineplayer% [and (store|save) [the] result in %-objects%]
harbor admin set (gold|mulbineul) of %offlineplayer% to %number% [and (store|save) [the] result in %-objects%]

harbor load (gold|mulbineul) [balance] of %offlineplayer% into %objects%
harbor load (gold|mulbineul) rank of %offlineplayer% into %objects%
harbor load top %number% (gold|mulbineul) [players] into %objects% [with balances in %-objects%]
harbor load [last] %number% (gold|mulbineul) history [entries] of %offlineplayer% into %objects% [with reasons in %-objects%] [with dates in %-objects%]
```

- 원장 사유: give·transfer 받는 쪽 `SCRIPT_GRANT`, take·transfer 보내는 쪽 `SCRIPT_TAKE`, admin set은 `ADMIN_GRANT`/`ADMIN_TAKE`. 참조 ID는 `skript:<UUID>`입니다.
- give·take 결과는 플레이어마다 참/거짓입니다. take는 잔액이 모자라면 거짓이고 아무것도 빼지 않습니다.
- transfer는 한 트랜잭션입니다. 한쪽이라도 실패하면 둘 다 되돌리고 결과는 거짓입니다.
- admin set 결과는 바꾼 뒤 잔액입니다.
- rank는 1부터, 잔액이 0이면 값 없음. top은 최대 100명(30초 캐시), history는 최대 200줄이고 최신순입니다 (변화량 · 사유 이름 · 날짜).

```
command /송금 <offlineplayer> <integer>:
    trigger:
        harbor transfer arg-2 gold from player to arg-1 and store the result in {_ok}
        if {_ok} is true:
            send "&a%arg-2%G를 보냈습니다" to player
        else:
            send "&c잔액이 모자랍니다" to player

command /골드순위:
    trigger:
        harbor load top 10 gold players into {_p::*} with balances in {_b::*}
        loop {_p::*}:
            send "%loop-index%. %loop-value% - %{_b::%loop-index%}%G" to player
```

**재화 이벤트 추가 값** (`on harbor currency change` 안에서):

```
event-player                        # 접속 중일 때
[the] harbor (changed|new) balance  # 바뀐 뒤 잔액
[the] harbor currency [change] reason   # 원장 사유 이름 (SCRIPT_GRANT, FISH_SELL …)
```

## 우편 (Core, 구현은 Harbor-Economy)

```
harbor load unclaimed mail count of %offlineplayer% into %objects%
harbor mail is available         harbor mail (isn't|is not) available
```

Harbor-Economy가 없으면 우편 문법은 아무것도 하지 않습니다 (`harbor mail is available`로 확인).
골드 우편은 열지 않습니다. 받을 때 원장에 MAIL_GOLD로 기록되어 스크립트 지급(SCRIPT_GRANT) 감사에서 빠지기 때문입니다. 오프라인 플레이어에게 골드를 줄 때는 `harbor give N gold to …`를 쓰세요.

## 문구 · 화면 (Core)

```
send harbor message %string% to %players% with [parsed] (placeholders|values) %strings%
[the] harbor message text [of] %string% [with [parsed] (placeholders|values) %-strings%]
harbor message %string% exists       harbor message %string% (doesn't|does not) exist
show harbor action[ ]bar message %string% [with [parsed] (placeholders|values) %-strings%] to %players%
show harbor title message %string% [with subtitle message %-string%] [with [parsed] (placeholders|values) %-strings%] to %players% [for %-timespan%]
show harbor title %string% [with subtitle %-string%] to %players% for %timespan%
show harbor ambient action[ ]bar %string% to %players% with quiet time %timespan%
```

- 자리 표시는 `"이름=값"` 문자열 목록입니다. 이름은 소문자·숫자·`_`·`-`.
  - 기본은 값을 **글자 그대로** 넣습니다 (플레이어 입력을 넣어도 안전).
  - `parsed`를 쓰면 값도 MiniMessage로 읽습니다 (`"<gold>100G"`).
- `harbor message text`는 Skript 글자(색 코드)로 바꾼 값입니다. 클릭·호버는 사라지니 채팅으로 보낼 때는 `send harbor message`를 쓰세요.
- `harbor message` 키 문법은 messages_ko.yml 키, `show harbor title %string%`은 MiniMessage 글자 그대로입니다.
- ambient 액션바: 최근 `quiet time` 안에 다른 액션바가 나갔으면 보내지 않습니다 (퀘스트 방향 안내처럼 계속 띄우는 안내용).

```
send harbor message "skript.lottery_won" to player with placeholders "player=%player%", "prize=1000"
show harbor title message "skript.boss_warning" with subtitle message "skript.boss_warning_sub" to all players for 4 seconds
```

### 보스바

```
show harbor boss[ ]bar %string% with (title|name) %string% [with progress %-number%] [with colo[u]r %-string%] to %players%
harbor set boss[ ]bar %string% progress (of|for) %players% to %number%
hide harbor boss[ ]bar %string% (from|of|for) %players%
hide all harbor boss[ ]bars (from|of|for) %players%
```

- 첫 값은 스크립트가 정하는 보스바 이름입니다. 같은 이름으로 다시 보이면 제목·진행도·색만 바뀝니다.
- 진행도 0~1 (기본 1), 색 `pink · blue · red · green · yellow · purple · white` (기본 white). 제목은 MiniMessage.
- 퇴장·서버 이동 때 사라집니다.

```
show harbor bossbar "event" with title "<aqua>바다 축제" with progress 1 with color "blue" to all players
every 10 seconds:
    add -0.01 to {festival}
    harbor set bossbar "event" progress of all players to {festival}
```

### 대화창 (Dialog)

```
show harbor notice [dialog] %string% [with body %-strings%] to %players%
show harbor confirm [dialog] %string% [with body %-strings%] with buttons %string% and %string% to %player% and (store|save) [the] answer in %objects%
show harbor choice [dialog] %string% [with body %-strings%] with (options|choices) %strings% [in %-number% columns] to %player% and (store|save) [the] answer in %objects%
show harbor [multiline] input [dialog] %string% [with body %-strings%] [with label %-string%] [with default %-string%] [with max length %-number%] with buttons %string% and %string% to %player% and (store|save) [the] (answer|input) in %objects%
```

- 글자는 모두 MiniMessage입니다. 본문은 줄 목록.
- confirm 결과: 첫 버튼 `true`, 둘째 버튼 `false`. choice 결과: 고른 순번(**1부터**). input 결과: 입력한 글자(앞뒤 공백 제거, 최대 길이 기본 256).
- 버튼 글자는 스크립트가 줍니다 (코드에 기본 문구가 없음).
- ESC로 닫으면 결과가 없고 **아래 줄이 실행되지 않습니다.**

```
command /복권:
    trigger:
        show harbor confirm "<gold>복권" with body "<gray>100G로 복권을 삽니다." with buttons "<green>산다" and "<red>그만둔다" to player and store the answer in {_yes}
        {_yes} is true
        harbor take 100 gold from player and store the result in {_ok}
        if {_ok} is not true:
            send "&c골드가 모자랍니다" to player
            stop
        show harbor choice "<gold>번호 고르기" with options "1번", "2번", "3번" in 3 columns to player and store the answer in {_n}
        send "%{_n}%번을 골랐습니다" to player

command /닉네임메모:
    trigger:
        show harbor input "메모" with label "내용" with max length 40 with buttons "저장" and "취소" to player and store the input in {_t}
        harbor set flag "skript/memo" for player to {_t}
```

### 소리

```
play harbor sound %string% (to|for) %players% [with volume %-number%] [with pitch %-number%]
play harbor sound %string% at %locations% [with volume %-number%] [with pitch %-number%]
stop harbor sound %string% (for|of) %players%
```

- 이름공간이 없으면 Harbor 소리 이벤트(`harbor:<키>`, sounds.yml)입니다. `minecraft:…`는 그대로 재생합니다.
- 리소스팩에 그 소리가 없거나 팩을 쓰지 않으면 sounds.yml의 바닐라 대체 소리가 납니다 (HarborSounds 규칙).
- volume·pitch는 sounds.yml 값에 곱해집니다.

```
play harbor sound "fishing/legendary_catch" to all players
play harbor sound "minecraft:block.bell.use" at location of player with volume 2
```

## 서버 이동 (나머지, Core)

```
harbor send %players% to server %string% [and (store|save) [the] result[s] in %-objects%]
harbor send %players% to [the] lobby and (store|save) [the] result[s] in %objects%
harbor send %players% to instance [server] %string% [and (store|save) [the] result in %-objects%]
```

- 모두 프록시의 이동 절차(저장 → 세션 넘김)를 거칩니다. 결과는 성공하면 `"ok"`, 아니면 프록시가 준 사유입니다.
- `to server`: 그 서버로 바로 보냅니다. **본 서버(main) 입장은 대기열을 거쳐야 하니** 본 서버로는 `harbor send … to sea`(출항)를 쓰세요. 이 문법은 운영용 보조 서버로 보낼 때 씁니다.
- `to instance`: 플레이어들을 한 파티로 그 인스턴스 서버에 보냅니다. 서버 배정(harbor:instance)은 보통 Boss가 하므로, 직접 쓰는 것은 운영·테스트용입니다. 일부만 성공하면 결과는 `partial:…`입니다.

## 파티 (Core, 구현은 Harbor-Boss)

```
[the] harbor party members of %player%       # 파티원 (자기 포함, 파티가 없으면 자기만)
[the] harbor party leader of %players%
[the] harbor party role of %players%         # leader · captain · harpooner · reeler · mechanic · quartermaster · member
[the] harbor party size of %players%         # 파티가 없으면 1
%players% (is|are) in a harbor party
%players% (isn't|is not|aren't|are not) in a harbor party
%player% (is|are) in [the] same harbor party as %player%
%player% (isn't|is not|aren't|are not) in [the] same harbor party as %player%
```

Harbor-Boss가 없으면 모두 혼자인 것으로 봅니다. 파티를 만들고 바꾸는 문법은 없습니다.

## 월드 보더 (Core)

```
harbor show world border at %location% with size %number% [with warning distance %-number%] (to|for) %players%
harbor show warning border (to|for) %players%      # 보더는 멀리 두고 화면만 붉게 (경고 효과)
harbor clear world border (of|for) %players%
```

- 플레이어 한 명당 보더는 하나라 Core가 우선순위로 나눕니다. 스크립트는 **가장 낮은 우선순위**(World 환경 경고와 같은 자리)를 씁니다. 보스전·낚시 장력 경고가 있으면 그쪽이 보이고, 끝나면 스크립트 보더로 돌아옵니다.
- World 모듈 환경 경고와 자리를 같이 쓰므로 둘 중 나중 것이 남습니다.
- warning distance를 빼면 0 (붉은 화면 없음).

## 콘텐츠 · 외부 이벤트 (Core)

```
harbor reload content [types %-strings%] [and (store|save) [the] result in %-objects% [with errors in %-objects%]]
harbor validate content [types %-strings%] and (store|save) [the] result in %objects% [with errors in %-objects%]
on harbor content reload:
[the] harbor reloaded content types        # 이벤트 안에서: 이번에 다시 읽은 형식 이름들

harbor emit [external] event %string% [for %-offlineplayer%] [with data %-strings%]
```

- reload: 형식을 비우면 전체. 오류가 하나라도 있으면 적용하지 않습니다. 결과 참/거짓, 오류는 줄 목록입니다. validate는 적용하지 않고 검사만 합니다.
- 외부 이벤트: Redis `harbor:events`로 나가 Harbor-Web 웹훅·REST로 전달됩니다.
  - 유형은 `skript.lottery_won`처럼 점으로 이은 소문자·숫자·밑줄 (형식이 틀리면 무시).
  - 데이터는 `"이름=값"` 목록이고 정수·소수·true/false는 그 타입으로, 나머지는 글자로 보냅니다.

```
on harbor content reload:
    broadcast "&7콘텐츠가 다시 로드됐습니다: %harbor reloaded content types%"

harbor emit event "skript.lottery_won" for player with data "prize=1000", "round=12"
```

## 디스플레이 리그 (Core)

`content/rigs/*.yml`의 리그를 Display 엔티티 묶음으로 띄우고 애니메이션을 재생합니다.

```
spawn harbor rig %string% at %location% [with animation %-string%] [with scale %-number%] [(for|to) %-players%] [and (store|save) [the] (id|rig) in %-objects%]
play animation %string% on harbor rig %string%
play animation %string% once on harbor rig %string%       # 한 번 재생 후 이전 애니메이션으로
stop animation of harbor rig %string%
move harbor rig %string% to %location% [over %-timespan%]
show harbor rig %string% to %players%
show harbor rig %string% to everyone
hide harbor rig %string% from %players%
make [part %-string% of] harbor rig %string% (glow|stop glowing)
remove harbor rig %string%

[the] harbor rig [id] of %entities%            # 리그의 엔티티(부품·히트박스 포함) → 리그 ID
[the] harbor rig type of [rig] %string%        # harbor:rig/…
[the] harbor rig animation of [rig] %string%
[the] harbor rig location of [rig] %string%
[the] harbor rig entities of [rig] %string%
[all] harbor rig types                         # 콘텐츠에 있는 리그 종류
[all] spawned harbor rigs                      # 지금 떠 있는 리그 ID
harbor rig %string% (is valid|exists)          harbor rig %string% (isn't|is not) (valid|existing)
```

- 리그 종류는 `rig/…`(접두사 생략 가능), 띄운 리그는 **리그 ID**(UUID 글자)로 가리킵니다. `spawn … and store the id in {_rig}`로 받아 두세요.
- `for %players%`를 주면 그 플레이어들에게만 보이는 개인 리그입니다.
- 리그는 저장되지 않습니다. 청크가 언로드되거나 서버가 꺼지면 사라집니다. 오래 둘 리그는 `harbor rig … exists`로 확인하고 다시 띄우세요.
- 히트박스 클릭 콜백·리그 이벤트는 스크립트에 열려 있지 않습니다. `on right click on entity`와 `harbor rig of event-entity`로 대신합니다.

```
command /고래:
    trigger:
        spawn harbor rig "fish/whale" at location of player with animation "swim" and store the id in {whale}
        wait 5 seconds
        play animation "jump" once on harbor rig {whale}

on right click on entity:
    set {_r} to harbor rig of event-entity
    {_r} is set
    send "리그 %harbor rig type of {_r}%" to player
```

## 가구 · 커스텀 블록 (Core)

```
harbor furniture item %string%                 # 가구 아이템 (furniture/… , 접두사 생략 가능)
harbor custom block item %string%              # 커스텀 블록 아이템 (block/…)
[the] harbor furniture id of %itemstacks%      %itemstacks%'[s] harbor furniture id
[the] harbor custom block id of %blocks/locations/itemstacks%      # 놓인 블록·위치·아이템 모두 됨
%blocks/locations/itemstacks%'[s] harbor custom block id
%blocks/locations/itemstacks% (is|are) [a] harbor custom block[s] [%-string%]
%blocks/locations/itemstacks% (isn't|is not|aren't|are not) [a] harbor custom block[s] [%-string%]
%itemstacks% (is|are) [a] harbor furniture [item[s]] [%-string%]
%itemstacks% (isn't|is not|aren't|are not) [a] harbor furniture [item[s]] [%-string%]
```

`harbor item "furniture/…"`(README)도 같은 아이템을 만듭니다. 개수는 `%number% of`로 정합니다.

```
give harbor furniture item "fishing_chair" to player
give 32 of harbor custom block item "marble_tile" to player
if target block is a harbor custom block "marble_tile":
    send "대리석 타일입니다" to player
```

### 가구 회수 이벤트

```
on harbor furniture (remove|pick[ ]up):
harbor furniture removal is allowed        harbor furniture removal (isn't|is not) allowed
harbor (allow|deny) [the] furniture removal
harbor (return|don't return|do not return) [the] removed furniture item
```

| 값 | 내용 |
|---|---|
| `event-player` | 회수하는 플레이어 |
| `event-location` | 가구가 놓인 블록 바닥 가운데 |
| `event-string` | 가구 ID |
| `event-item` | 놓을 때 쓴 아이템 (1개, 복사본) |

- 웅크리고 우클릭으로 회수하기 직전에 옵니다. `cancel event`면 그대로 둡니다.
- 놓은 사람이나 `harbor.furniture.manage` 권한이 있으면 처음부터 허용, 아니면 `harbor allow furniture removal`로 허용해야 회수됩니다.
- `don't return`이면 아이템을 돌려주지 않습니다 (스크립트가 따로 보관할 때).

```
on harbor furniture pickup:
    player has permission "skript.plaza.furniture"
    harbor allow furniture removal
```

### 커스텀 블록 놓기 · 부수기 (바닐라 이벤트로)

Core에 커스텀 블록 전용 이벤트가 없어서 바닐라 이벤트와 위 문법을 같이 씁니다.

```
on place:
    harbor custom block id of player's tool is "harbor:block/marble_tile"
    send "대리석을 놓았습니다" to player

on break:
    event-block is a harbor custom block "marble_tile"      # 부서지기 전이라 아직 판정됨
    send "대리석을 부쉈습니다" to player
```

가구 놓기·상호작용·앉기 이벤트와 위치로 가구 찾기·놓기·치우기 문법은 아직 없습니다 (Core API 없음).

## 직업 (Harbor-Jobs)

```
[the] harbor job level of %players%                    %players%'[s] harbor job level
[the] harbor job exp[erience] of %players%             # 지금 레벨 안의 경험치
[the] harbor next level exp[erience] of %players%      # 다음 레벨까지 필요한 경험치 (최대 레벨이면 0)
[the] harbor level cap of %players%                    # 지금 단계의 레벨 상한
[the] harbor quality cap of %players%                  # 품질 상한 ★ (1차 3, 2차 4, 3차 5)
[the] harbor capstone [skill] of %players%             # 가진 캡스톤 스킬 ID
[the] harbor capstone cooldown of %players%            # 남은 재사용 대기 (timespan)
[the] harbor capstone weekly uses [left] of %players%  # 이번 주 남은 횟수 (주간 제한 없는 캡스톤은 -1)

harbor (give|grant) %number% job exp[erience] to %players% [and (store|save) [the] (result|granted amount) in %-objects%]
make %player% request harbor job advance[ment] to tier %number% [and (store|save) [the] result in %-objects%]
harbor consume [a] weekly capstone use of %offlineplayer% [and (store|save) [the] result in %-objects%]
```

- 경험치 지급은 출처 `ADMIN`으로 들어가고 감쇠·따라잡기·`exp_gain` 보정이 적용됩니다. 결과는 실제로 준 양입니다 (바로 저장, 기다리지 않음).
- 전직 요청은 전직 확인 화면을 띄우고(2차는 계열 선택 먼저) 결과를 기다립니다: `ok` · `wrong_tier` · `level_too_low` · `not_enough_gold` · `cancelled` · `busy` · `failed`.
- 주간 캡스톤 사용 결과는 참/거짓 (남은 횟수가 없으면 거짓).

| 이벤트 | 값 |
|---|---|
| `on harbor level up` | `event-offlineplayer` · `event-player`, `event-number` = 새 레벨 |
| `on harbor job advance` | `event-offlineplayer` · `event-player`, `event-number` = 새 단계, `event-string` = 계열 |
| `on harbor job exp gain` | `event-offlineplayer` · `event-player`, `event-number` = 받을 양, `event-string` = 출처 (fishing · quest · boss · admin). **취소 가능** |
| `on harbor job line change` | `event-string` = 새 계열, `harbor previous job line` = 이전 계열 |
| `on harbor skill (invest\|allocate)` | `event-string` = 스킬 ID, `event-number` = 새 등급 |
| `on harbor skill reset` | `event-string` = 범위 (common · line · all), `event-number` = 돌려받은 물비늘 |
| `on harbor capstone use` | `event-string` = 캡스톤 스킬 ID |

```
[the] harbor exp gain amount      # job exp gain 안에서. set · add · remove · reset 가능
```

```
on harbor job exp gain:
    {skript::double_exp} is true
    set harbor exp gain amount to harbor exp gain amount * 2

on harbor job advance:
    broadcast "&e%event-offlineplayer% 님이 %event-number%차 전직했습니다! (%event-string%)"
```

## 시즌 (Harbor-Season)

```
[the] harbor season week                      # 지금 주차 (0 = 시작 전, 13 이상 = 연장)
[the] harbor season name
[the] harbor season (length|weeks)            # 예정 주 수
[the] harbor season week shift [hours]
[the] harbor season (state|phase)             # planned · active · transitioning · archived
[the] harbor season is (extended|in overtime)       [the] harbor season (isn't|is not) (extended|in overtime)
[the] harbor season start [date]
[the] harbor season end [date]                # 예정 종료 (연장되면 그 뒤로도 계속)
[the] harbor season week %number% start [date]
[the] next harbor daily reset [date]
[the] next harbor weekly reset [date]
```

```
command /시즌:
    trigger:
        send "%harbor season name% %harbor season week%주차 / 종료 %harbor season end%" to player
```

### 칭호

```
%players% (has|have) harbor title %string%                         # 접속 중인 플레이어 (메모리)
%players% (doesn't|does not|don't|do not) have harbor title %string%
[the] equipped harbor title of %players%                           # 장착 칭호 ID
[the] harbor title (display [name]|name) of %string%               # 칭호 표시 글자
[the] equipped harbor title (display [name]|name) of %players%
equip harbor title %string% (for|to|on) %players%                  # 가진 칭호만
unequip harbor title (of|for|from) %players%

harbor load [owned] titles of %offlineplayer% into %objects%       # 가진 칭호 ID 목록 (얻은 순서)
harbor check [if] %offlineplayer% (has|owns) title %string% and (store|save) [the] result in %objects%   # 오프라인도 됨
harbor admin grant title %string% to %offlineplayers% [and (store|save) [the] result[s] in %-objects%]
harbor admin revoke title %string% from %offlineplayers% [and (store|save) [the] result[s] in %-objects%]
```

- 칭호는 원래 퀘스트·도감·시즌 보상으로 받습니다. **`harbor admin grant/revoke`는 운영용**입니다 (이벤트 상품, 잘못 준 칭호 회수).
- grant 결과: `granted` · `already_owned` · `unknown_title` · `failed`. 참조 ID `skript:<UUID>`. revoke 결과: 가지고 있었으면 참.

### 주간 제한

보스 주간 보상(`boss:<보스>`)·주간 제작(`craft:<레시피>`)과 같은 저장소입니다. 여러 서버에서 동시에 불러도 한도를 넘지 않습니다.

```
harbor load weekly limit %string% of %offlineplayer% with limit %number% into %objects% [with used count in %-objects%]   # 남은 횟수
harbor consume weekly limit %string% of %offlineplayer% with limit %number% [and (store|save) [the] result in %-objects%]  # 1회 사용, 성공하면 참
harbor refund weekly limit %string% of %offlineplayer% [in week %-number%] [and (store|save) [the] result in %-objects%]   # 1회 되돌림
[the] harbor weekly limit week                 # 지금 주 번호 (환불할 때 쓰는 값)
```

- 키는 소문자·숫자·`_ : / . -`, 96자까지. 스크립트용은 `skript:…`로 쓰세요. `boss:…`·`craft:…`를 쓰면 실제 보스·제작 횟수를 건드립니다.
- 사용 뒤 보상 지급에 실패하면 사용 직전의 `harbor weekly limit week`를 넣어 환불하세요 (주가 넘어가도 맞는 주에 환불).

```
command /주간상자:
    trigger:
        set {_week} to harbor weekly limit week
        harbor consume weekly limit "skript:weekly_chest" of player with limit 3 and store the result in {_ok}
        if {_ok} is not true:
            send "이번 주에는 더 열 수 없습니다" to player
            stop
        harbor give 500 gold to player and store the result in {_paid::*}
        if {_paid::1} is not true:
            harbor refund weekly limit "skript:weekly_chest" of player in week {_week}
```

### 순위 · 명예의 전당

```
[the] harbor rank of %players% (on|in) [board] %string% [in league %-string%]            # 캐시된 상위 N 안의 순위
[the] harbor ranking score of %players% (on|in) [board] %string% [in league %-string%]
[the] harbor ranking names (of|on|in) [board] %string% [in league %-string%]             # 1위부터 이름
[the] harbor ranking scores (of|on|in) [board] %string% [in league %-string%]
[the] harbor ranking players (of|on|in) [board] %string% [in league %-string%]
[all] [of] [the] harbor ranking boards                                                    # season/rankings.yml 보드 ID
harbor admin refresh rankings [and (store|save) [the] result in %-objects%]               # 지금 다시 집계 (잠금을 못 잡으면 거짓)
harbor load hall of fame %string% [of season %-number%] into %objects% [with values in %-objects%] [with subjects in %-objects%]
```

- 순위는 집계 서버가 10분마다 갱신한 캐시입니다 (DB를 읽지 않음). 상위 N 밖이면 값이 없습니다.
- 리그 `tier1` · `tier2` · `all` (기본 all).
- 명예의 전당: 시즌을 빼면 지금 시즌. 결과는 이름(당시) · 값 · 대상(어종·보스 ID, 없으면 `-`) 목록입니다.

```
command /레벨순위:
    trigger:
        set {_names::*} to harbor ranking names of board "level"
        set {_scores::*} to harbor ranking scores of board "level"
        loop {_names::*}:
            loop-index parsed as integer <= 10
            send "%loop-index%. %loop-value% Lv.%{_scores::%loop-index%}%" to player
```

### 시즌 이벤트

| 이벤트 | 값 |
|---|---|
| `on harbor daily reset` | `event-string` = 새 게임 날짜 (yyyy-mm-dd). 서버마다 따로 옴 |
| `on harbor weekly reset` | `event-number` = 초기화 직후 주차. 서버마다 따로 옴 |
| `on harbor season week change` | `event-number` = 새 주차, `harbor previous season week` = 이전 주차 |
| `on harbor title grant` | `event-offlineplayer` · `event-player`, `event-string` = 칭호 ID (새로 얻었을 때만) |
| `on harbor title equip` | `event-offlineplayer` · `event-player`, `event-string` = 칭호 ID (해제면 값 없음) |
| `on harbor season enter` | `event-player`, `event-number` = 시즌 번호, `harbor previous season` · `harbor removed stack count` |
| `on harbor hall of fame update` | `event-offlineplayer` = 새 1위, `event-string` = 부문, `event-number` = 기록, `harbor hall of fame subject`, `harbor hall of fame previous (holder\|leader)` |

```
on harbor daily reset:
    broadcast "&a새로운 하루가 시작됐습니다. 일일 의뢰가 갱신됐습니다!"

on harbor title grant:
    send "&6새 칭호: %harbor title name of event-string%" to event-player
```

## 없는 것 (API가 없어서)

- **가구:** 위치에 가구 놓기·치우기, 위치·엔티티로 가구 찾기, 가구 놓기·상호작용·앉기 이벤트, 커스텀 블록 전용 놓기·부수기 이벤트.
- **직업:** 스킬 보유·등급 조회, 스킬 투자·초기화, 관리자용 레벨·단계·계열 설정. 이벤트(투자·초기화)는 받을 수 있습니다.
- **시즌 패스:** Harbor-Shop 쪽 문법입니다.
- **파티 만들기·초대·탈퇴**, 리그 히트박스 클릭 콜백.
