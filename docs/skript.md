# Harbor-Skript

선택 설치하는 Skript 애드온이다. Skript가 있을 때만 문법을 등록하고, 각 Harbor 모듈 문법도 그 모듈이 켜져 있을 때만 등록한다. 재화는 원장(`SCRIPT_GRANT` · `SCRIPT_TAKE`)으로만, 아이템은 `ItemService`로만 다룬다. 문법 전체와 예시는 [harbor-skript/README.md](../harbor-skript/README.md)에 있다.

## 할 수 있는 것

| 분류 | 문법 예시 |
|---|---|
| 재화 | `harbor gold of player` 늘리기·줄이기 (원장 기록), `on harbor currency change` |
| 아이템 | `harbor item "item/bait/earthworm"`, `harbor id of player's tool`, `is harbor item` |
| 문구·화면 | `send harbor message`, harbor title·actionbar, harbor mail 보내기 (오프라인 포함) |
| 서버 이동 | `harbor send %players% to lobby\|sea`, `harbor server [role]` |
| 플레이어 정보 | 레벨·직업 단계·계열·평판·시즌 번호 |
| 플래그 | 플레이어·서버 플래그 확인·값·설정·해제 (`harbor set flag …`) |
| 퀘스트 | 상태·완료 여부·진행 수, 수락·포기·추적, `harbor quest signal/set`으로 목표 진행, `on harbor quest accept/complete` |
| 낚시 | `on harbor fish catch` (어종·등급·지역·크기·품질·첫 포획) |
| 도감·기록 | 도감 %·등록 여부·개인 최고 크기, 도감 열기, `on harbor collection register`, `on harbor record broken` |
| NPC | 평판 등급·호감도, 컷씬 재생, NPC 대화 열기 |
| 직업 | `on harbor level up`, `on harbor job advance` |
| 시즌 | 주차·이름·칭호 보유·장착·해제 |
| 경제 | 시세 계수·다음 시간 예측, `on harbor auction sold` |
| 해역 | `on harbor sea event start/end` |

## 지킬 것

- **진행·보상 기록:** Skript 변수에 저장하지 않는다. 서버마다 따로 저장되기 때문이다. 플래그나 Harbor API를 쓴다.
- **Harbor 아이템:** 바닐라 `give`로 흉내 내지 않고 `harbor item`을 쓴다.
- **채팅 형식:** `on chat`으로 가공하지 않는다. Harbor가 프록시로 보내므로 `messages_ko.yml chat.format`에서 바꾼다.
- **원장 감사:** 스크립트가 준 재화는 원장 사유 `SCRIPT_GRANT` · `SCRIPT_TAKE`로 따로 볼 수 있다 (README의 감사 쿼리).

## 설정

`balance.yml skript.title_seconds`: harbor title 표시 시간 (기본 2.5초).

## 없는 것

- 퀘스트북·판매·경매 GUI 열기: 그 모듈에 공개 진입점이 없다. 도감 열기만 있다.
- Skript에서 새 퀘스트 목표 유형 등록: 콘텐츠 검사가 Skript보다 먼저 돈다. 이미 있는 유형을 진행시키는 것만 된다.
- 칭호·호감도 직접 지급: 공개 API가 없어서 정상 보상 경로를 쓴다.

## 실서버 확인 필요

같은 클래스를 여러 패턴에 쓰는 등록 방식(Skript 2.17), 이벤트 안에서만 쓰는 식, 선택 인자(`%-…%`), 오프라인 대상 이벤트의 동작.
