# Harbor-Economy

판매·시세·NPC 상점·우편함·수리·제작·경매장을 맡는다. 골드·물비늘은 모두 Core 원장(`CurrencyService`)을 거친다. 자세한 조작법은 [harbor-economy/README.md](../harbor-economy/README.md)에 있다.

## 주요 기능

- **판매 상자 (`/판매`):**
  - 물고기를 넣으면 마릿수와 합계 견적이 바로 보인다. 같은 어종을 많이 팔 때의 미끄러짐도 반영된다.
  - 판매는 한 트랜잭션이다. `FISH_SELL` 원장과 고유 ID 장부 `SOLD`로 처리하고, 실패하면 아이템을 돌려준다.
- **귀한 물고기 보호:**
  - 잠근 아이템(`/잠금`, PDC `harbor:item_locked`), 고유 번호 어종, ★5 어종이 대상이다.
  - 일괄 판매에서 빠지고, 직접 넣으면 판매를 두 번 눌러야 한다.
  - 잠근 아이템은 경매 등록도 막힌다.
- **시세:**
  - 매시 정각에 분산 잠금을 잡은 서버 하나가 계수를 계산하고 Redis `harbor:market`으로 알린다.
  - `/시세` 시세판에서 보이는 것은 '시세 정보'·'시세 예측' 스킬에 따라 달라진다: 현재 계수, 24시간 추세 ▲▼, 급변 강조, 다음 시간 예측.
- **경매장 (`/경매`):**
  - 분류·정렬(최신·가격·마감 임박·등급)·검색(입력 화면)·내 경매·거래 기록이 있다.
  - 등록할 때 지금 최저가와 최근 낙찰 평균을 보여 준다.
  - 부정 거래가 의심되는 판매 대금은 보류된다 (`FraudMonitor`).
- **우편함 (`/우편함`):**
  - 보상·낙찰품·반환품이 가방이 차면 여기로 온다.
  - 하나씩 받거나 모두 받을 수 있다. 같은 참조 ID로 다시 보내면 무시된다 (재시도 안전).
- **NPC 상점:** 좌클릭 1개, Shift+좌클릭 한 묶음, 우클릭 개수 입력. 일일 한도와 호감도 할인이 있다.
- **제작 (`/제작`):** 근처 제작 스테이션의 레시피만 보인다. 재료 회수, 비용 지불, 결과 지급을 한 트랜잭션으로 처리하고 주간 제한이 있다.
- **수리 (`/수리`)·큰손 거래 (`/큰손`):** 큰손 거래는 경제 계열 캡스톤으로 주 1회다.
- **거래 내역 (`/거래내역`):** 내 골드·물비늘 원장 최근 200줄.

## 플레이어 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/판매` · `/판매 손` · `/판매 전부` | `harbor.economy.sell` | 판매 상자 · 손에 든 것 · 인벤토리 전부(귀한 물고기 제외) |
| `/잠금` | `harbor.economy.lock` | 손에 든 아이템 잠금 켜기/끄기 |
| `/시세` · `/시세 <어종>` | `harbor.economy.market` | 시세판 · 한 어종 |
| `/경매` · `/경매 등록 [가격]` · `/경매 검색 <이름>` · `/경매 내목록` | `harbor.economy.auction` | 경매장 (등록·취소는 경매 게시판 반경 안) |
| `/우편함` | `harbor.economy.mail` | 우편함 |
| `/거래내역` | `harbor.economy.history` | 원장 기록 |
| `/수리` | `harbor.economy.repair` | 장비 수리 |
| `/제작` | `harbor.economy.craft` | 근처 제작 스테이션 |
| `/큰손` | `harbor.economy.big_deal` | 큰손 거래 |

## 관리자 명령 (`/harbor …`)

| 명령 | 설명 |
|---|---|
| `market recompute` · `market set <어종> <계수>` | 시세 다시 계산 · 계수 고정 |
| `mail gold <플레이어> …` · `mail held <플레이어>` | 우편으로 골드 보내기 · 보류 우편 보기 |
| `npcshop open <상점>` | 상점 화면 열기 |
| `auction cancel <id>` · `auction list <플레이어>` · `auction expire` | 경매 취소 · 목록 · 만료 처리 |
| `econ report` · `econ flags` · `econ flag release\|resolve …` | 경제 보고 · 부정 거래 의심 목록 · 보류 풀기 / 처리 |
| `econ stations` · `craft open …` | 제작 스테이션 확인 · 제작 화면 열기 |

`harbor.admin.economy.alert` 권한이 있으면 부정 거래·시세 중단 알림을 받는다.

## 콘텐츠·설정

- **콘텐츠:**
  - `content/shops.yml`: NPC 상점
  - `content/recipes.yml`: 제작 레시피
  - `content/stations.yml`: 제작 스테이션
  - `content/auction_refs.yml`: 경매 참고가
- **balance.yml `economy`:**
  - `market`: 시세 계수 식 (기울기·최소·최대), `market.board`: 시세판 보합 기준·강조 수
  - `auction`: 수수료·기간·`price_info_days`
  - 판매 미끄러짐과 부정 거래 기준
- **DB:** `market_*`, `auction_*`, `mail`, `craft_*`, `fraud_*` 테이블 (마이그레이션 `economy/V1~V3`)

## 다른 모듈과의 연결

- **내놓는 서비스:** `PriceService`(시세·견적), `MailService`(Core api 구현), `ShopService`, `CraftService`, `AuctionService`, `RepairService`, `SaleService`
- **대신 거래하기:** `SaleService.sellHeld · sellInventory · sellBox`, `ShopService.buy`, `AuctionService.listHeld · buy · listing`, `CraftService.craft`, `RepairService.repairHeld`. 플레이어 화면과 같은 흐름(TradeGuard, 원장 한 트랜잭션, 실패 시 반환)에서 확인 창만 뺀 것이고 `TradeResult`(상태·골드·개수·매물 번호)를 메인 스레드에서 돌려준다. Skript 문법은 harbor-skript 문서의 "대신 거래하기".
- **이벤트:** `FishSoldEvent`, `AuctionListedEvent`, `AuctionSoldEvent`, `ShopPurchaseEvent`, `ItemCraftedEvent`, `MarketUpdatedEvent`, `TradeFlaggedEvent`. Harbor-Web이 `economy.*` 외부 이벤트로 옮긴다.
- **Core:** `CurrencyService.history`로 원장을 읽는다 (`/거래내역`, REST `/v1/players/{id}/ledger`).

## 주의 사항 (실서버 확인 필요)

- **판매 상자 복제 확인:** 판매 상자를 연 채 서버 이동이나 접속 끊김이 일어날 때, 창 닫기와 인벤토리 저장의 순서를 확인해야 한다.
- **입력 화면:** 경매 검색·상점 개수 입력은 Paper 실험 API `Dialogs.input`을 쓴다.
- **MariaDB 쿼리:** 경매 등급 정렬(`FIELD()`)과 평균가 쿼리를 MariaDB 11.4에서 확인해야 한다.
- **아이템 겹치기:** 같은 물고기라도 잠근 것과 안 잠근 것은 PDC가 달라서 겹쳐지지 않는다.
