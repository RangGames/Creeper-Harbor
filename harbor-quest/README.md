# Harbor-Quest 사용법

퀘스트·의뢰 게시판·항구 연대기 모듈. 이 문서는 운영자·콘텐츠 디자이너용 요약이다. 설계는 CreeperWiki docs 20 6장.

## 플레이어

| 명령 | 하는 일 |
|---|---|
| `/퀘스트` | 퀘스트북(상자 GUI). 탭은 진행 중 · 받을 수 있음 · 완료이고, 게시판 · 연대기 · 추적 표시 바로 가기가 있다 |
| `/퀘스트 목록` | 예전 Dialog 목록 |
| `/퀘스트 표시` | 화면 위 추적 보스바를 모든 추적 퀘스트에 켜기/끄기 (기본은 시간 제한 이벤트 퀘스트만. 목표 위치는 늘 위치 표시 막대에 뜬다) |
| `/퀘스트 추적 <퀘스트>` · `/퀘스트 포기 <퀘스트>` | 명령으로 추적·포기 |
| `/의뢰` · `/연대기` | 어부 길드 게시판 · 항구 연대기 |

퀘스트북 조작: 좌클릭은 추적(보고 NPC가 없고 보고할 수 있으면 보고), 우클릭은 자세히, Shift+우클릭은 포기. 받을 수 있음 탭에서 시작 NPC가 없는 퀘스트는 좌클릭으로 받는다.

## 관리자

| 명령 | 권한 | 하는 일 |
|---|---|---|
| `/harbor quest editor [퀘스트]` | `harbor.admin.quest.editor` | 게임 안 퀘스트 편집기 |
| `/harbor quest gui <플레이어>` | `info` (버튼마다 `set`·`complete`) | 그 플레이어의 퀘스트 상태·목표 진행을 클릭으로 고친다 |
| `/harbor quest info · set · progress · complete · reset · board reroll · server` | 각 권한 | 예전 명령 그대로 |

관리 GUI와 편집기도 명령과 같이 모든 변경을 `admin_log`(`quest.*`)에 남긴다.

### 퀘스트 편집기

YAML을 몰라도 상자 GUI로 퀘스트를 만들고 고친다.

1. `/harbor quest editor`에서 퀘스트를 고르거나 **새 퀘스트**(유형 → ID → 이름)를 누른다.
2. 편집 화면에서 다음을 정한다.
   - 이름·설명: 입력 화면
   - 시작·보고 NPC: 좌클릭은 목록, 우클릭은 가장 가까운 NPC, Shift는 비우기
   - 요구 조건: 레벨·전직·주차·선행 퀘스트
   - 수락 제안 방식
   - 목표
   - 보상: 클릭으로 증감, 손에 든 Harbor 아이템 추가
3. 목표는 유형을 고른 뒤 대상을 고른다.
   - 대상 고르는 법: 낚기는 등급·어종·지역, 대화는 NPC, 방문은 **지금 서 있는 곳 + 반경**이나 지역
   - 수량·품질·문구·안내 위치도 여기서 정한다
4. **미리보기**는 저장될 YAML을 채팅으로 보여 준다. **저장**하면 다음 순서로 처리된다.
   1. `plugins/Harbor/content/quests/<파일>.yml`에서 그 항목만 바꿔 쓴다. 다른 항목의 주석·서식은 그대로다.
   2. quest 콘텐츠를 리로드한다.
   3. 검사에 걸리면 파일을 원래대로 되돌리고 오류를 채팅으로 보여 준다. 고치던 초안은 남아 있다.

알아 둘 것:

- **저장소 반영:** 서버에서 고친 파일은 DefaultContent가 "고친 파일"로 보고 기본값으로 덮어쓰지 않는다. 저장소 `content/quests/`에는 사람이 옮긴다.
- **주석:** 편집기로 저장한 항목 **안쪽**의 주석은 사라진다. 다른 항목의 주석은 남는다.
- **목표 `key`:** 저장된 진행을 이어 붙이는 기준이다. 편집기는 기존 목표의 key를 바꾸지 않는다. 불러올 때 생략된 key를 실제 값으로 적어 둔다.
- **목표 순서:** 바꾸면 차례대로(sequence) 퀘스트는 진행 순서가 바뀐다.
- **ID:** 만든 뒤에는 바꾸지 않는다.

## YAML로 쓸 때

목표는 기본형과 한 줄 축약형 둘 다 된다.

```yaml
objectives:
  - {key: mackerel, type: catch, target: fish/harbor_mackerel, count: 3}   # 기본형
  - {catch: fish/harbor_mackerel, count: 3}       # 축약형: key는 catch_harbor_mackerel
  - {talk: npc/bawi, waypoint: npc/bawi}          # key: talk_bawi
  - {visit: "point:harbor_main,-14,72,-50,8"}
  - {job_level: 5}                                # 값이 수량인 유형 (job_level · job_tier · reputation)
```

- **key 생략:** 축약형은 `유형_대상` key를 만든다. 기본형은 예전처럼 순서 번호(`o1`, `o2`…)다.
- **key가 겹칠 때:** 같은 대상을 두 번 쓰는 것처럼 key가 겹치면 `key:`를 직접 적는다.
- **축약형 제한:** 축약형에는 `type`·`target`을 같이 쓰지 않는다. 값이 수량인 유형에는 `count`도 쓰지 않는다.

수락 제안을 어떤 형태로 보여 줄지 퀘스트마다 정할 수 있다 (비우면 시작 NPC의 대화 방식을 따른다).

```yaml
- id: quest/region/harbor_tackle_errand
  offer_style: hologram     # dialog · hologram · title · chat · actionbar
```

## NPC 대화 방식 (Harbor-Npc)

NPC 대화는 Dialog 화면 말고도 머리 위 말풍선(홀로그램)·타이틀·채팅·액션바로 보여 줄 수 있다. 퀘스트 수락 제안과 NPC의 퀘스트 목록도 같은 방식을 따른다.

```yaml
# npcs.yml
- id: npc/peddler
  dialog_style: hologram
# dialogs/*.yml 노드
    rumor:
      pages: [...]
      style: title      # 노드마다 덮어쓰기
      auto: 5           # 말풍선·타이틀 등에서 5초마다 다음 쪽
```

- Dialog 화면이 아닌 방식의 선택지는 채팅 버튼으로 나온다.
- 같은 NPC를 다시 우클릭하면 다음 쪽으로 넘어간다.
- 기본값은 `balance.yml npc.dialog.default_style`이다.

## 알림·표시 설정 (balance.yml `quest`)

```yaml
quest:
  journal:
    style: gui              # /퀘스트: gui 또는 dialog
  tracker:
    locator: true           # 추적 목표를 위치 표시 막대 waypoint로
    bossbar: true           # 추적 보스바 (기본은 시간 제한 이벤트 퀘스트에만)
    bossbar_all: false      # true면 모든 추적 퀘스트에 보스바가 기본
  notify:                   # chat · title · actionbar · none (쉼표로 여러 개). <사건>_main은 메인 퀘스트 전용
    accepted: chat
    accepted_main: chat, title
    ready: chat, actionbar
    completed_main: chat, title
```

타이틀·액션바 문구는 `messages_ko.yml`의 `quest.notify.title/subtitle/actionbar.<사건>`에 있다.
