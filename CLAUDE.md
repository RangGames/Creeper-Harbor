# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 프로젝트

크리퍼 항구: 바닐라 클라이언트로 접속하는 마인크래프트 낚시 서버. Velocity 프록시 + Purpur 26.3 백엔드 위에서 도는 Harbor-* 플러그인 모음이다. Java 25만 쓴다 (Kotlin은 build-logic의 Gradle 스크립트에만).

### 설계 문서 (코드보다 먼저 확인)

기획·설계는 저장소 밖에 있다 (CreeperWiki `docs/`, 같은 내용이 Google Docs "크리퍼 항구 (낚시서버) 기획"의 탭으로도 있다: 탭 16/17/11/7 = docs 19/20/14/10). 위치를 모르면 사용자에게 묻는다.

- `19-plugin-common-core.md`: Common·Core 설계 (패키지, DB 계층, 원장, Redis, 세션 프로토콜, 시작 순서)
- `20-plugin-modules.md`: 모듈별 상세 설계. 0장 "모듈 간 조정 사항"에 소유권, 0.2 Core 추가 API, 0.3 의존 방향, 결정 사항 D1~D14가 있다
- `14-data-design.md`: ID 규칙, 콘텐츠 YAML 필드, DB 테이블, Redis 채널
- `10-balance.md`: 수치. 코드에 수치를 박지 않고 `content/balance.yml`로 뺀다
- 문서에 없는 테이블·필드·수치를 만들면 주석에 "(추가 제안)" 또는 "제안값"으로 표시한다 (docs 20의 관례)

## 빌드·실행

Gradle 9.9.1 wrapper, JDK 25 toolchain. (Windows에서는 `gradlew.bat`)

```
./gradlew build                         # 전체 빌드 + 테스트 + spotlessCheck
./gradlew spotlessApply                 # 서식 자동 수정 (google-java-format AOSP, 4칸). build 전에 실행
./gradlew :harbor-common:test --tests "wiki.creeper.harbor.common.ContentLoaderTest"   # 테스트 하나
./gradlew dist                          # 서버에 넣을 jar만 build/dist/{purpur,velocity}/plugins에 모음 (shadowJar·build도 마지막에 실행)
./gradlew :harbor-core:shadowJar        # Core jar (Common 포함)
./gradlew :harbor-core:runServer        # 모든 Purpur 모듈을 넣은 로컬 테스트 서버 (run-paper, Paper 26.3)
./gradlew validateContent               # content/ YAML을 서버와 같은 로더로 검사 (harbor-tools)
./gradlew resourcePack -PpackDir=<팩 폴더> [-PpackMeta=<pack.mcmeta가 든 zip>]   # 클라이언트 팩 zip + SHA-1 (build/resourcepack, dist -PpackDir=도 가능)
./gradlew packIndex -Ppack=<팩 zip>     # 리소스팩의 item_model·소리 목록 → content/resource_pack.yml (ItemModels가 이 목록에 있는 모델만 씀)
./gradlew rigImport -Prigs=<팩 assets/harbor/rigs/fish>   # 팩의 어종 리그(harbor_rig_v1) → content/rigs/fish/*.yml
```

- 컴파일러는 `-Werror` + ErrorProne + NullAway(ERROR, 테스트 제외)다. 경고 하나도 빌드 실패이므로: 버리는 Future는 `var _ = ...`, `e.getMessage()`처럼 널일 수 있는 값은 `String.valueOf`로 감싸고, 중첩 타입에는 `Outer.@Nullable Inner`, 널 가능 값은 JSpecify `@Nullable`, 레코드 접근자 결과는 지역 변수에 담은 뒤 널 검사한다.
- `*IT` 테스트는 Testcontainers(MariaDB 11.4, Redis 7.4)를 쓴다. Docker가 없으면 건너뛴다.
- 버전은 `gradle/libs.versions.toml`에서만 관리한다. `server-runtime` 묶음(HikariCP·MariaDB·Lettuce·Flyway)은 jar에 넣지 않고 Core의 PluginLoader가 Maven에서 받는다. 목록은 빌드 때 `harbor-libraries.txt` 리소스로 생성된다.

## 아키텍처

### 모듈 구조와 의존 방향

```
harbor-common   순수 Java (Paper·Velocity import 금지). Core와 Proxy jar 안에 shadow로 들어간다
harbor-core     Purpur 플러그인. Common을 api 의존으로 노출
harbor-proxy    Velocity 플러그인. Common + Lettuce를 shadow (relocate)
harbor-<게임>    jobs, fishing, collection, npc, quest, economy, world, boss, season, shop, wildlife
                → paper-plugin.yml에서 Harbor-Core에 load: BEFORE, join-classpath: true
harbor-tools    콘텐츠 검증 CLI (CI용). Common의 로더를 그대로 쓴다
build-logic     컨벤션 플러그인: harbor.java / harbor.paper-plugin / harbor.paper-module / harbor.velocity-plugin
content/        디자이너가 편집하는 YAML (fish/*.yml, items/*.yml, regions.yml, balance.yml, messages_ko.yml)
```

- 게임 모듈 간 의존 (paper-plugin.yml 기준, `?`는 선택 의존. 모두 Core 필수). 새 의존을 넣을 때 이 방향을 거스르면 Gradle 순환이 생긴다:
  - Npc, Season → Core만
  - Jobs → Npc? / Fishing → Jobs, Season?
  - Economy → Jobs, Fishing, Npc, Season / World → Jobs, Fishing, Economy?, Npc?, Season?
  - Collection → Fishing, Season, Npc?, World?, Shop? / Boss → Fishing, Season, Npc?, Shop?
  - Quest → Npc, Jobs, Fishing, Collection, Season, World?, Boss? / Shop → Fishing, Collection, Season, Quest?, World?, Boss? / Wildlife → Fishing, World
- 배포 jar: Core·Proxy만 shadowJar(Common 포함)이고 게임 모듈은 일반 jar가 곧 플러그인이다 (런타임은 Core가 제공). harbor-common jar는 서버에 넣지 않는다. Core jar에는 content/가 기본 콘텐츠로 들어간다 (`DefaultContent`).
- 모듈은 다른 모듈의 내부 클래스를 쓰지 않고 각 모듈의 `api` 패키지(인터페이스·이벤트·레코드)만 쓴다. Core의 공개 API는 `wiki.creeper.harbor.core.api`이고 진입점은 `Harbor.api()`.
- 게임 모듈 메인 클래스는 `HarborModule`(JavaPlugin 상속)을 상속하고 `enable(ModuleContext)`에서 콘텐츠 형식·플레이어 컴포넌트·`/harbor` 하위 명령·마이그레이션을 등록한다. `onEnable/onDisable`은 final이다.
- 테이블 소유권: players·sessions·admin_log·ledger·pearl_ledger = Common, player_season·fish_ledger·player_flags·server_flags = Core, 나머지는 각 모듈. 마이그레이션은 `src/main/resources/db/migration/<모듈ID>/`, 이력 테이블은 `flyway_<모듈ID>`. 운영 중 스키마는 열 추가만 한다.

### 꼭 지켜야 하는 흐름

- **스레드:** 메인 스레드에서 DB·Redis·파일 입출력 금지. `Database.async`/`Tasks.async`(가상 스레드)로 하고 결과는 `tasks.main()` 실행기로 메인 스레드에 돌려 반영한다. `HarborPlayer`와 `ModifierService`는 메인 스레드 전용.
- **재화:** 골드·물비늘은 `CurrencyService`(→ Common `LedgerWriter`)로만 바꾼다. 잔액 행 잠금 → 음수 거부 → 원장 기록(참조 ID 유니크로 중복 지급 차단) → 잔액 갱신이 한 트랜잭션. 잔액 열을 직접 UPDATE하지 않는다 (매일 원장 합계 검사가 깨진다). 시즌 초기화도 `SEASON_RESET` 원장 기록으로 한다. 새 사유는 `LedgerReason` enum에 추가해야 쓸 수 있다. 진주는 Shop 모듈이 같은 원장 도구로 따로 관리한다.
- **쓰기 원칙:** 재화·경매·통발 수확은 즉시 트랜잭션, 도감·기록·퀘스트 진행은 `WriteBehindQueue`(키별 마지막 값만 씀). 플레이어 저장은 항상 `player:<uuid>:` 키로 큐에 넣고, 퇴장·서버 이동 때는 `flushNow(prefix)`로 그 플레이어 것만 즉시 쓴다 (오래된 자동 저장이 나중에 덮어쓰지 않게).
- **서버 이동 세션:** Redis `harbor:lock:<uuid>` (값 `OWNED|서버` / `HANDOFF|출발|도착`, 30초 TTL, Lua CAS). 도착 서버는 `AsyncPlayerPreLoginEvent`에서 `awaitClaim`으로 handoff를 확인하기 전까지 로드하지 않는다. Redis 장애 시 이동을 막는다 (복제 방지 우선). 구현: Common `session/`, Core `player/PlayerManager`.
- **플레이어 데이터:** 모듈 데이터는 `PlayerComponent<T>`로 붙이고 값은 변경 불가 객체로 둔다 (`HarborPlayer.set`으로 교체 → dirty → 5분 자동 저장). 로드는 접속 전에 병렬로 하고, 실패하면 접속을 거부한다.
- **콘텐츠:** `ContentType.Entries`(ID가 있는 항목 목록) / `ContentType.Single`(파일 하나). 파싱은 `YamlNode` 게터가 오류를 줄·열 위치와 함께 모으고 예외를 던지지 않는다. 다른 콘텐츠 참조는 `ParseContext.ref(형식이름, id, pos)`로 등록해 일괄 검사한다 (대상 형식이 미등록이면 건너뜀). 서버 API가 필요한 검사는 Core에서 `ContentCheck`로 추가한다. 오류가 하나라도 있으면 리로드를 거부하고 기존 스냅샷을 유지한다. 스냅샷은 변경 불가라 어느 스레드에서 읽어도 된다.
- **수치 보정:** 스킬·버프·이벤트는 `Modifier`(ADD 합 → MULTIPLY 곱 → balance.yml `stats.<키>.min/max` 클램프)로만 수치를 바꾼다.
- **문구:** 코드에 문구를 쓰지 않고 `messages_ko.yml` 키 + MiniMessage(`MessageService`).
- **ID:** `harbor:<종류>/<이름>` (소문자·숫자·밑줄). `FishId`, `ItemId` 등 래퍼가 접두사를 검사한다. ID는 바꾸지 않는다.

## 현재 진행 상태

Purpur 게임 모듈 11개와 Common·Core·Proxy·tools가 모두 있고 전체 빌드·단위 테스트·콘텐츠 검사를 통과한다. 실제 서버(26.3)에서는 아직 돌려 보지 않았다 (Phase 1 검증 항목: Mannequin NPC, 보트 속도·해피 가스트 조종, 관전 카메라, Interaction 히트박스 크기, 리소스팩 모델).

- Core: 0.2의 추가 API(MailService·PartyService 인터페이스, WorldBorderService 우선순위 중재, HandoffBlocker, `service()`·`redis()`, `PlayerService.saveNow`)와 디스플레이 리그 재생(5.10, `rigs()` → RigService/RigInstance, content/rigs/*.yml)이 있다. Common에 DonorTier, AccountLinkReader, 리그 수학(common.rig)이 있다.
- 모듈 간 연결은 각 모듈 `api` 패키지 + Bukkit ServicesManager로 한다. 선택 의존 모듈의 클래스는 그 모듈이 켜져 있을 때만 불러오는 bridge 클래스에 둔다. 의존 방향은 docs 20 0.3을 따른다 (World는 Quest·Collection·Shop에 의존하지 않고, 그쪽이 World api에 구현을 등록한다. Shop은 일부 이벤트를 클래스 이름으로 반사 구독한다).
- 남은 것: 해저 유적 던전과 Phase 3 보스 4종(콘텐츠), 메인 4~7장 퀘스트(클라이맥스 조건), 리소스팩 모델·구조물(.nbt)·해도 이미지, 패킷 전용 표시 방식, Tebex 서명 형식 확인(법률 검토 항목), Core가 시즌 번호를 season_meta에서 읽기·프록시 점검 전환 API, 채팅에 이름 색·칭호 싣기.
- 미니게임 조작은 Shift 감기 + A/D 방향 (consumable 방식은 Phase 1 검증 뒤). 모듈 콘텐츠 형식은 서버 API 없는 `XxxContent`로 모아 플러그인과 harbor-tools가 같이 등록한다. season 열이 있는 새 테이블은 content/season/reset_plan.yml에 넣어야 Season이 로드를 허용한다.

로컬 실행: `plugins/Harbor/harbor-infra.yml`(처음 실행 때 기본값 생성)에 DB·Redis 접속 정보를 넣고 `./gradlew :harbor-core:runServer`. 콘텐츠는 Core jar에 기본값으로 들어 있어 `plugins/Harbor/content`에 없는 파일만 시작 때 만들어진다 (있는 파일은 덮어쓰지 않으므로 콘텐츠 갱신은 직접 복사한다. runServer는 저장소 content/를 동기화).

## 작업 규칙

- 커밋 메시지는 영어로 쓴다.
- 공개 저장소다. 로컬 경로, 개인 환경, 개인 정보를 코드·문서·커밋에 넣지 않는다.
