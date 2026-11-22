# 낚시 · 도감 · 퀘스트 · 야생 물고기 문법

Harbor-Skript가 Harbor-Fishing · Harbor-Collection · Harbor-Quest · Harbor-Wildlife에 대해 등록하는 문법 전체입니다.
각 묶음은 **그 모듈이 켜져 있을 때만** 등록됩니다 (꺼진 모듈의 문법을 쓰면 스크립트를 읽을 때 "알 수 없는 문법" 오류).

공통 규칙:

- ID는 `harbor:`를 빼고 써도 되고, 앞의 종류도 빼도 됩니다. `"harbor:fish/harbor_mackerel"` = `"fish/harbor_mackerel"` = `"harbor_mackerel"` (어종 인자 자리에서). 지역은 `region/…`, 퀘스트는 `quest/…`.
- 등급·상태·이유 같은 값은 소문자 키로 돌려줍니다 (`legendary`, `active`, `line_broke` …).
- `이벤트 안에서만`으로 표시한 식은 그 이벤트 밖에서 쓰면 스크립트를 읽을 때 오류가 납니다.
- 모든 문법은 메인 스레드에서 돌고 DB를 기다리지 않습니다. **비동기 값**으로 표시한 식은 마지막으로 받아 온 값을 돌려주고 뒤에서 새로 받아 옵니다. 처음 읽을 때는 값이 없을 수 있고, 값은 몇 초 늦을 수 있습니다.
- 바꾸는 문법(`set` · `add` · `remove`, 효과)도 Harbor API를 거칩니다. 골드가 드는 것(통발 칸 구매)은 모듈이 원장으로 처리합니다.

## 낚시 (Harbor-Fishing)

### 낚시 상태 · 천장 · 해역 이벤트

```
[the] harbor fishing state of %players%            # idle · cast · waiting · bite · hooked · minigame · result
%players% (is|are) harbor fishing                  # 조건 (부정형: isn't · aren't)
[the] harbor pity of %players% for [fish] %string%  # 전설 천장 카운터 (읽기 전용)
[the] harbor missed big (count|fish) of %players%   # 직업 품질 상한에 걸려 놓친 대어 수 (이번 접속)
[the] harbor expected share of [fish] %string% in [region] %string%   # 기본 출현 비율 0~1
[all] [the] active harbor sea events               # 지금 낚시에 적용 중인 해역 이벤트 ID 목록
harbor sea event %string% is active                # 조건 (부정형: isn't active)
```

### 물고기 아이템

```
%itemstacks% (is|are) [a] harbor fish [item[s]]    # 조건
%itemstacks% (is|are) [a] harbor trap catch[es]    # 통발에서 나온 물고기인지
[the] harbor fish species of %itemstacks%          # 어종 ID
[the] harbor fish name of %itemstacks%             # 어종 이름
[the] harbor fish grade of %itemstacks%            # common … mythic
[the] harbor fish size of %itemstacks%             # cm
[the] harbor fish quality of %itemstacks%          # 1~5
[the] harbor fish (uid|unique id) of %itemstacks%  # ★5·전설·신화만
[the] harbor fish season of %itemstacks%
[the] harbor fish catcher of %itemstacks%          # offline player
[the] harbor fish caught (date|time) of %itemstacks%
```

만들기 (관리용):

```
[a] harbor fish item %string% [with size %-number%] [with quality %-number%] [caught by %-offlineplayer%]
harbor give [a] catch of [fish] %string% to %players% [with %-number% bonus star[s]]
```

- `harbor fish item`: 아이템만 만듭니다 (`ItemService.createFish`). 크기를 빼면 어종 범위에서 무작위, 품질을 빼면 ★1. ★5·전설·신화는 고유 ID가 fish_ledger에 기록되므로 **식을 읽을 때마다 새 기록이 생깁니다.** 변수에 한 번 담아 쓰세요.
- `harbor give catch`: 실제 포획처럼 처리합니다 (크기·품질 굴림, 직업 상한, `on harbor quality roll`, 원장 기록, `on harbor fish catch` 출처 `boss`). 아이템은 인벤토리에 넣고 넘치면 발밑에 떨굽니다.

```
set {_f} to harbor fish item "fish/harbor_mackerel" with size 42 with quality 3
give {_f} to player
```

### 통발 (비동기 값)

```
[the] harbor trap count of %players%                         # = (number|amount) of harbor traps of %players%
[all] [the] harbor trap ids of %players%
[the] harbor trap stored (count|catches) of %players%        # 모든 통발의 미수확 수 합
[the] harbor trap (region|item|tier|slots|extra slots|bait|stored count|owner|location|next cycle) of trap %number%
refresh harbor traps of %players%                            # 바로 다시 받아 오기
make %players% buy [a] harbor trap slot for trap %number%    # 칸 확장 (골드는 원장 TRAP_SLOT_EXPAND)
```

`… of trap %number%`는 이미 받아 온 플레이어의 통발에서만 찾습니다. 먼저 `harbor trap ids of player`를 한 번 읽거나 `refresh harbor traps`를 쓰세요.

### 장력 미니게임 (스크립트로 시작)

```
start [a] harbor tension [minigame] for %players% [titled %-string%] [with grade %-string%] [with hp %-number%] [with tag %-string%]
%players% (is|are) in [a] harbor tension [minigame]
[the] harbor tension of %players%            # 0~1. add · remove · set 가능 (장력 흔들기)
[the] harbor tension (hp|health) of %players%  # 상대 체력 비율 1→0. remove = 최대 체력 대비 피해
[the] harbor tension phase of %players%
stop harbor tension of %players% [with result %-string%]   # caught · line_broke · escaped(기본) · aborted
on harbor tension end:                       # event-player, event-string = 결과, harbor tension tag
```

제목은 MiniMessage, 등급 기본값은 `rare`, hp를 빼면 등급 기본값입니다. 아이템·경험치·미끼는 다루지 않으므로 보상은 `on harbor tension end`에서 직접 줍니다.

```
command /결투:
    trigger:
        start harbor tension for player titled "<red>심해의 그림자" with grade "epic" with tag "duel"

on harbor tension end:
    harbor tension tag is "duel"
    event-string is "caught"
    harbor give catch of "fish/abyss_eel" to player
```

### 이벤트

| 이벤트 | event-값 | 이벤트 안에서만 쓰는 식 |
|---|---|---|
| `on harbor fishing cast` · `on harbor cast` | player, string = 굴린 어종, number = 입질까지 틱, location = 찌 | `harbor cast region` · `harbor cast bait` · `harbor cast wait [ticks]`, 조건 `harbor cast is over a [fish] school` |
| `on harbor fish roll` (취소 가능) | player, string = 어종 | `harbor rolled fish` (**set 가능**: 다른 어종으로 바꾸기), `harbor roll bait` |
| `on harbor fish bite` · `on harbor bite` | player, string = 어종 | |
| `on harbor quality roll` · `on harbor size roll` | player, string = 어종, number = 크기 | `harbor rolled size` (**set·add·remove**), `harbor rolled quality` (**set·add·remove**, 1~5로 자름), 조건 `harbor rolled quality is capped` |
| `on harbor minigame end` | player, string = 결과, number = 걸린 틱 | `harbor minigame result` · `harbor minigame (ticks|time)` |
| `on harbor fishing session end` | player, string = 이유 (reeled_early · missed_bite · bait_changed · interrupted) | `harbor session end reason` |
| `on harbor fish catch` · `on harbor catch` | player, string = 어종, number = 크기 | `harbor caught fish [id]` · `harbor caught fish name` · `harbor catch grade` · `harbor catch region` · `harbor catch size` · `harbor catch quality` · `harbor catch source` (rod · trap · boss) · `harbor catch (uid|unique id)`, 조건 `harbor catch is [a] first catch` · `is released` · `is capped` |
| `on harbor trap harvest` | player, number = 통발 ID, string = 지역 | `harbor harvested fish` (어종 목록) · `harbor harvested count` · `harbor harvest trap [id]` |
| `on harbor tension end` | player, string = 결과 | `harbor tension tag` |

어느 낚시 이벤트에서든 `harbor event fish [id]` · `harbor event fish name` · `harbor event fish grade`로 그 이벤트의 어종을 읽을 수 있습니다.

다음 물고기 강제: Fishing에 "다음 물고기" API가 없으므로 굴림 이벤트에서 바꿉니다.

```
on harbor fish roll:
    player has permission "harbor.event.lucky"
    set harbor rolled fish to "fish/golden_carp"

on harbor quality roll:
    add 1 to harbor rolled quality
```

## 도감 · 기록 · 수족관 (Harbor-Collection)

### 도감

```
[the] harbor [season] collection percent[age] of %players%          # 시즌 완성률 0~100
[the] harbor total collection percent[age] of %players%             # 누적
[the] harbor collection percent[age] of %players% in [region] %string%   # 지역별 (시즌, 신화 제외)
%players% (has|have) registered harbor fish %string%                # 누적 등록 여부
%players% (has|have) registered harbor fish %string% this season    # 시즌 등록 여부
[the] harbor [total] collection (count|catches) of %players% for [fish] %string%
[the] harbor [total] collection (size|max size) of %players% for [fish] %string%
[the] harbor [total] collection quality of %players% for [fish] %string%
[the] harbor [total] collection first catch [date] of %players% for [fish] %string%
[the] harbor season collection (count|catches|size|max size|quality|first catch [date]) of %players% for [fish] %string%
[the] harbor (best|record) size of %players% for [fish] %string%
open harbor collection [of %-offlineplayer%] (to|for) %players% [at page %-number%]
```

도감 칸은 실제 포획으로만 바뀝니다 (스크립트용 등록·삭제 API 없음).

### 힌트

```
[the] harbor hint level of %players% for [fish] %string%    # 0~3. set · add 가능 (올리기만, 출처 skript)
harbor grant [a] random hint to %players% [in region %-string%]
```

### 서버 기록

```
[the] harbor server top (names|sizes|players) for [fish] %string% [in league %-string%] [limit %-number%]
```

리그는 `tier1` · `tier2` · `all`(기본), 최대 10개. 이번 시즌 본 서버 캐시 기준입니다.

### 수족관 · 방생

```
[the] harbor aquarium points
[the] harbor aquarium [donated] species [count]
[the] harbor aquarium donatable [species] [count]
[the] harbor aquarium (rate|progress)          # 0~1
[the] harbor aquarium stage
open harbor aquarium donation box (to|for) %players%
make %players% donate [their] held fish to [the] harbor aquarium     # 확인 화면 뒤 기증
harbor release is (available|open)                                   # 조건 (climax_done 이후)
make %players% release [their] held harbor fish                      # 확인 화면 뒤 방생
```

### 개인 수족관 진열 (Harbor-World가 있을 때, 비동기 값)

```
[the] harbor display slot count of %players%
[the] harbor display theme slot count of %players%
[all] [the] harbor displayed fish of plot %number%
[the] harbor displayed fish in slot %number% of plot %number%
harbor display fish %string% in slot %number% of plot %number% as %players%   # 편집 권한·칸 수·누적 등록 검사
harbor admin clear display slot %number% of plot %number%                    # 관리용, 권한 검사 없음
```

### 이벤트

| 이벤트 | event-값 | 이벤트 안에서만 쓰는 식 |
|---|---|---|
| `on harbor collection register` | offlineplayer, string = 어종 | `harbor collection scope` (total · season), `harbor registered grade` |
| `on harbor collection milestone` | offlineplayer, string = 지역 이름·단계 키, number = 완성률 | `harbor milestone kind` (region_complete · total_percent), `harbor milestone title` |
| `on harbor record broken` | offlineplayer, string = 어종, number = 크기 | `harbor record scope` (personal · server), `harbor record league`, `harbor record previous size` |
| `on harbor aquarium donate` | offlineplayer, string = 어종, number = 점수 | `harbor donated grade`, `harbor donated quality` |
| `on harbor aquarium stage change` | number = 새 단계 | `harbor aquarium event season` |
| `on harbor fish release` | offlineplayer, string = 어종, number = 크기 | `harbor released grade` |

## 퀘스트 (Harbor-Quest)

### 상태 · 진행

```
[the] harbor quest state of %players% for [quest] %string%     # locked · available · active · ready · completed …
[the] (number|amount) of active harbor quests of %players%
[all] [the] active harbor quests of %players%                  # 진행 중 퀘스트 ID 목록
%players% (has|have) completed harbor quest %string%
[the] harbor quest (name|type|cycle) of %players% for [quest] %string%       # 진행 중일 때만
[all] [the] harbor quest objectives of %players% for [quest] %string%        # 목표 키 목록
[the] harbor quest progress of %players% for [quest] %string% [objective] %string%
[all] [the] harbor quest objective types                       # 등록된 목표 유형
```

### 행동 · 목표 신호

```
make %players% accept harbor quest %string%
make %players% abandon harbor quest %string%       # 메인 퀘스트는 불가
make %players% turn in harbor quest %string%       # ready 상태만, 납품·보상 처리
make %players% track harbor quest %string%
harbor quest signal %string% [with target %-string%] [by %-number%] for %players%   # 횟수 목표 +
harbor quest set %string% [with target %-string%] to %number% for %players%         # 도달값 목표 (내려가지 않음)
```

신호 유형은 퀘스트 콘텐츠에 있는 유형이어야 합니다 (`harbor quest objective types`로 확인).

### 연대기 · 서버 진행 (서버 진행은 비동기 값)

```
[all] [the] harbor chronicle pieces of %players%
[the] (number|amount) of harbor chronicle pieces of %players%
%players% (has|have) harbor chronicle piece %string%
open harbor chronicle (to|for) %players%
[the] harbor chapter %number% clear count
[the] harbor climax (donation rate|required rate|clears|required clears|active players)
harbor climax is ready
```

### 이벤트

| 이벤트 | event-값 | 이벤트 안에서만 쓰는 식 |
|---|---|---|
| `on harbor quest accept` | player, string = 퀘스트 | |
| `on harbor quest complete` | offlineplayer, string = 퀘스트 | `harbor completed quest cycle`, `harbor quest reward (gold|mulbineul|exp|reputation|title|chronicle [piece])`, `harbor quest reward items` (목록) |
| `on harbor quest abandon` | player, string = 퀘스트 | |
| `on harbor quest ready` | player, string = 퀘스트 | |
| `on harbor quest progress` | player, string = 퀘스트, number = 새 진행값 | `harbor progress objective` · `harbor progress value` · `harbor progress (goal|target count)` |
| `on harbor quest state change` | offlineplayer, string = 퀘스트 | `old harbor quest state` · `new harbor quest state` |
| `on harbor chapter complete` | offlineplayer, number = 장 | `harbor completed chapter`, 조건 `harbor chapter is a recap` |
| `on harbor chronicle piece` | offlineplayer, string = 조각, number = 모은 수 | `harbor obtained chronicle piece` · `harbor chronicle owned count` · `harbor chronicle total count` |

퀘스트 이벤트 어디서든 `harbor event quest [id]`, 수락·완료에서는 `harbor event quest type`을 쓸 수 있습니다.

## 야생 물고기 (Harbor-Wildlife)

야생 물고기는 엔티티가 아니라 가상 개체라서 **물고기 ID(UUID 문자열)** 로 가리킵니다. 조회 값은 마지막 시뮬레이션 단계(최대 0.1초 전) 기준이고, 생성·제거는 다음 단계에 적용됩니다. 야생 물고기는 낚시 굴림에 영향을 주지 않습니다.

```
[the] harbor wild fish count
[all] [the] harbor wild fish (near|around) %location% [within %-number% [blocks]]   # 가까운 순, 기본 16칸
[the] harbor wild fish targeted by %player% [within %-number% [blocks]]             # 시선 레이캐스트, 기본 32칸
%strings% (is|are) [a] harbor wild fish                    # 살아 있는 ID인지
harbor wild fish %strings% (is|are) visible
harbor wild fish %strings% (is|are) scripted
[the] harbor wild (fish|species) of %strings%              # 어종 ID
[the] harbor wild species entry of %strings%               # wildlife/species.yml 항목 ID
[the] harbor wild location of %strings%
[the] harbor wild state of %strings%                       # cruise · idle · action · flee · approach · bite · school
[the] harbor wild medium of %strings%                      # water · lava · air · floor
[the] harbor wild school of %strings%
[the] harbor wild radius of %strings%
```

```
spawn [a] harbor wild fish %string% at %locations% [with action %-string%] [for %-number% seconds]
spawn [a] harbor wild school of %string% along %locations% [with %-number% fish]
[the] last spawned harbor wild (fish|school)
remove harbor wild fish %strings%
boost harbor wild fish in [region] %string% by %number% for %timespan% [only %-strings%] [with source %-string%]
clear harbor wild boost %string%
```

- `with action`이나 `for … seconds`를 주면 그 자리에 머물며 동작을 보여 주고 위협에 반응하지 않습니다 (연출용). 없으면 보통 물고기처럼 헤엄칩니다. 전설·신화도 만들 수 있습니다.
- 무리는 경로를 따라가다 마지막 점에서 흩어집니다. 마릿수를 빼면 어종 기본값.
- 밀도 증가는 같은 source로 다시 부르면 덮어씁니다. source 기본값은 `skript:<지역 이름>`.

```
command /회유:
    trigger:
        spawn harbor wild school of "fish/migration_leader" along {path::*} with 12 fish
        boost harbor wild fish in "region/coral_isles" by 2 for 30 minutes with source "skript:migration"
```

| 이벤트 | event-값 | 이벤트 안에서만 쓰는 식 |
|---|---|---|
| `on harbor wild fish spawn` (취소 가능) | location, string = 어종, number = 마릿수 | `harbor wild spawn reason` (natural · spot · scripted · path), `harbor wild spawn medium` |
| `on harbor wild fish startle` | player, string = 어종, location = 물고기 위치 | `harbor startled wild fish` (물고기 ID) |

## 공개 API가 없어 빠진 것

| 원하는 것 | 막힌 이유 |
|---|---|
| 낚시 중 현재 어종·입질·미니게임 장력 읽기 | `FishingService`가 상태(`session`)만 공개. 현재 어종은 이벤트 안에서만 |
| 다음 물고기 지정 | API 없음. `on harbor fish roll`에서 `set harbor rolled fish` |
| 천장 카운터 설정 · 놓친 대어 초기화 | `pity`는 읽기만 |
| 통발 수확·설치·미끼 넣기 | `TrapService`는 조회·칸 확장만 |
| 음식 버프 조회·적용 | Fishing api에 음식 버프 공개 진입점 없음 |
| 해역 이벤트·신화 창 시작/종료 | Fishing은 받은 상태만 공개(`activeSeaEvents`). 일정은 World 소관 |
| 시뮬레이션 결과 | `simulate`가 수천 번 굴리는 무거운 작업이라 노출하지 않음 |
| 도감 칸 등록·삭제, 주간 기록 | `CollectionService`·`RecordService`에 쓰기·주간 API 없음 |
| 퀘스트 강제 완료·초기화, 추적 중인 퀘스트 읽기, 목표 목표치(진행 중 목록) | `QuestService`에 없음 (목표치는 `on harbor quest progress`의 `harbor progress goal`에서만) |
| 의뢰 게시판 | Quest api에 게시판 공개 진입점 없음 |
| 수족관에 아이템 목록 기증 | `donateItems`는 아이템 소유권을 넘겨받는 방식이라 스크립트 아이템 식과 맞지 않음. 손에 든 물고기 기증·기증함만 |
