# 크리퍼 항구 플러그인 문서

크리퍼 항구는 바닐라 클라이언트로 접속하는 마인크래프트 낚시 서버다. Velocity 프록시와 Purpur 26.3 백엔드 위에서 Harbor-* 플러그인 묶음이 돈다. 이 폴더는 각 플러그인 모듈이 하는 일, 명령, 콘텐츠 파일, 설정 값을 운영자와 콘텐츠 디자이너에게 정리해 둔 문서다.

기획·설계 원문은 저장소 밖의 CreeperWiki `docs/`(19·20·14·10번 문서)에 있다. 이 문서는 지금 코드가 실제로 하는 일을 기준으로 쓴다.

## 모듈 목록

| 모듈 | 플랫폼 | 역할 | 문서 |
|---|---|---|---|
| Harbor-Common | (라이브러리) | 순수 Java 공용 코드. DB·원장·Redis·세션 잠금·콘텐츠 로더. Core·Proxy jar 안에 들어간다 | [core.md](core.md) |
| Harbor-Core | Purpur | 공통 플러그인. 콘텐츠·플레이어 데이터·재화·아이템·보정자·문구·인벤토리 동기화·리그·가구·리소스팩·`/harbor` | [core.md](core.md) |
| Harbor-Proxy | Velocity | 접속 진입점. 대기열·서버 이동·점검·제재·서버 간 채팅 | [proxy.md](proxy.md) |
| Harbor-Jobs | Purpur | 직업 단계·계열·레벨·스킬 트리·캡스톤 | [jobs.md](jobs.md) |
| Harbor-Fishing | Purpur | 캐스팅 판정·미니게임·크기·품질·통발·요리 버프·매크로 방지 | [fishing.md](fishing.md) |
| Harbor-Collection | Purpur | 도감·최대어 기록·항구 수족관·방류·개인 어항 전시 | [collection.md](collection.md) |
| Harbor-Npc | Purpur | NPC·대화(5가지 방식)·호감도·평판·떠돌이 NPC·컷씬 | [npc.md](npc.md) |
| Harbor-Quest | Purpur | 퀘스트·어부 길드 의뢰 게시판·항구 연대기·서버 진행도 | [quest.md](quest.md) |
| Harbor-Economy | Purpur | NPC 상점·판매·경매·시세·제작·수리·우편함 | [economy.md](economy.md) |
| Harbor-World | Purpur | 지역·해도·환경 디버프·선박·탈것·물고기 떼·보물 지도·해상 이벤트·하우징 | [world.md](world.md) |
| Harbor-Boss | Purpur | 보스전·파티·기여도·월드 보스·레이드 인스턴스 | [boss.md](boss.md) |
| Harbor-Season | Purpur | 시즌 시계·주간 제한·칭호·랭킹·명예의 전당·시즌 전환 | [season.md](season.md) |
| Harbor-Shop | Purpur | 진주(유료 재화)·코스메틱·시즌 패스·보물함·후원 등급·Tebex 결제 수신 | [shop.md](shop.md) |
| Harbor-Wildlife | Purpur | 바다를 헤엄치는 야생 물고기 (보이는 연출만, 낚시 판정에는 영향 없음) | [wildlife.md](wildlife.md) |
| Harbor-Web | Purpur (선택) | 외부 이벤트 발행·웹훅·읽기 전용 REST API | [web.md](web.md) |
| Harbor-Skript | Purpur (선택) | Skript 애드온. Skript가 있을 때만 문법 등록 | [skript.md](skript.md) |
| harbor-tools | CLI | 콘텐츠 검증(CI)·리소스팩 목록·리그 변환 | [tools.md](tools.md) |

모듈별 상세 사용법이 따로 있는 곳:

- [harbor-quest/README.md](../harbor-quest/README.md): 퀘스트북 GUI, 게임 안 퀘스트 편집기, YAML 축약형, 알림 설정
- [harbor-collection/README.md](../harbor-collection/README.md): 도감·기록·수족관 화면 구성
- [harbor-web/README.md](../harbor-web/README.md): 이벤트 type 목록, 웹훅 서명, REST 경로
- [harbor-economy/README.md](../harbor-economy/README.md)
- [harbor-skript/README.md](../harbor-skript/README.md)

## 의존 방향

모든 게임 모듈은 Harbor-Core에 필수로 의존한다 (`paper-plugin.yml`에서 `load: BEFORE`, `join-classpath: true`). `?`는 선택 의존이다.

```
                     Harbor-Common (순수 Java, Core·Proxy jar 안에 포함)
                     │
          ┌──────────┴───────────┐
     Harbor-Core              Harbor-Proxy (Velocity)
          │
          ├── Npc, Season                       → Core만
          ├── Jobs                              → Npc?
          ├── Fishing                           → Jobs, Season?
          ├── Economy                           → Jobs, Fishing, Npc, Season
          ├── World                             → Jobs, Fishing, Economy?, Npc?, Season?
          ├── Collection                        → Fishing, Season, Npc?, World?, Shop?
          ├── Boss                              → Fishing, Season, Npc?, Shop?
          ├── Quest                             → Npc, Jobs, Fishing, Collection, Season, World?, Boss?
          ├── Shop                              → Fishing, Collection, Season, Quest?, World?, Boss?
          ├── Wildlife                          → Fishing, World
          ├── Web (선택)                        → 다른 모듈 모두 선택
          └── Skript (선택)                     → Skript?
```

- 모듈끼리는 각 모듈의 `api` 패키지(서비스 인터페이스·이벤트·레코드)와 Bukkit ServicesManager로만 연결한다. 다른 모듈의 내부 클래스는 쓰지 않는다.
- 선택 의존 모듈의 클래스는 그 모듈이 켜져 있을 때만 불러오는 bridge 클래스에 둔다. 그래서 선택 모듈을 빼도 나머지가 켜진다.
- World는 Quest·Collection·Shop에 의존하지 않는다. 그쪽 모듈이 World api에 구현을 등록한다.

## 빌드와 배포

Windows가 주 개발 환경이다. macOS·Linux에서는 `gradlew.bat` 대신 `./gradlew`를 쓴다.

```bat
gradlew.bat spotlessApply     :: 서식 맞추기 (빌드 전에)
gradlew.bat build             :: 전체 빌드 + 테스트 + 서식 검사 (마지막에 dist도 실행)
gradlew.bat dist              :: 서버에 넣을 jar만 모으기
gradlew.bat validateContent   :: content/ YAML 검사
gradlew.bat :harbor-core:runServer   :: 모든 Purpur 모듈을 넣은 로컬 테스트 서버
```

`dist` 결과:

| 폴더 | 넣는 곳 | 들어 있는 jar |
|---|---|---|
| `build/dist/purpur/plugins/` | 모든 Purpur 백엔드(lobby·main·instance)의 `plugins/` | Harbor-Core(Common·기본 콘텐츠 포함), 게임 모듈 11개, Harbor-Skript(선택), Harbor-Web(선택) |
| `build/dist/velocity/plugins/` | Velocity 프록시의 `plugins/` | Harbor-Proxy(Common·Lettuce 포함) |
| `build/dist/resourcepack/` | (CDN 업로드용) | `-Ppack=<zip>` 또는 `-PpackDir=<폴더>`를 줬을 때만. 팩 zip과 `.sha1` |

- harbor-common jar는 따로 넣지 않는다. Core·Proxy jar 안에 들어 있다.
- 게임 모듈 jar에는 라이브러리가 없다. 런타임은 Core가 제공한다.
- HikariCP·MariaDB 드라이버·Lettuce·Flyway는 Core jar에 넣지 않는다. Core의 PluginLoader가 서버 시작 때 Maven에서 받는다. 첫 시작 때 서버가 Maven 저장소에 접속할 수 있어야 한다.
- 필요한 외부 서비스: MariaDB(테스트는 11.4), Redis(테스트는 7.4). 모든 백엔드와 프록시가 같은 DB·Redis를 본다.

## 서버 설정 기본 (`harbor-infra.yml`)

백엔드는 `plugins/Harbor/harbor-infra.yml`, 프록시는 `plugins/harbor-proxy/harbor-infra.yml`이다. 처음 켤 때 기본값으로 만들어진다. 게임 수치는 여기가 아니라 `content/balance.yml`에 둔다.

| 키 | 뜻 |
|---|---|
| `server.id` | 프록시에 등록한 서버 이름과 같아야 한다 (예: `main-1`, `lobby`) |
| `server.role` | `main`(본 서버) · `lobby` · `instance`(던전·레이드). 프록시는 `proxy` |
| `dev` | 메인 스레드 DB·Redis 입출력 감지 경고 |
| `database.*` | MariaDB 접속 정보. `pool_size` 기본값 main 20, lobby·instance 8, 프록시 5 |
| `redis.uri` | Redis 주소 |
| `season.id` · `season.start` | 이 서버가 돌리는 시즌 번호와 시작일. 시즌 전환 뒤 운영자가 바꾼다 |
| `time.zone` · `daily_reset` · `weekly_reset` | 일일·주간 초기화 시각 (기본 Asia/Seoul, 06:00, 월요일) |
| `content_dir` | 콘텐츠 폴더. 비우면 `plugins/Harbor/content` (이때만 기본 콘텐츠 자동 맞춤) |
| `lobby_server` | 로비 서버 ID |
| `resource_packs` | 로비가 보내는 필수 리소스팩 목록 (id·url·sha1) |
| `pack.build` | 리소스팩 자동 빌드. lobby 한 대에서만 켠다. CDN API 키가 들어가므로 이 파일은 커밋하지 않는다 |

프록시 쪽 대기열·점검·제재 설정은 `harbor-proxy.yml`에 있다 ([proxy.md](proxy.md)).

## 권한 규칙

- 플레이어 명령 권한은 대부분 `default: true`다. 권한이 없는 명령은 `/도움말`에서 자동으로 감춘다.
- `/harbor` 관리 명령은 `harbor.admin`이 있어야 보인다. 하위 명령마다 `harbor.admin.<하위 명령>`이 더 필요하다 (예: `/harbor fishing …` → `harbor.admin.fishing`). 모듈 하위 명령의 이 권한은 Core가 자동으로 건다. `paper-plugin.yml`에 적혀 있지 않은 노드는 Bukkit 기본값대로 OP에게만 있다.
- 일부 모듈은 그 아래에 권한을 한 번 더 나눈다 (예: `harbor.admin.boss.start`). 각 모듈 문서의 관리자 명령 표에 적었다.
- 모든 `/harbor` 실행은 `admin_log` 테이블에 남는다.

## 공통 개념

- **ID:** `harbor:<종류>/<이름>` (소문자·숫자·밑줄). YAML에서는 접두사 `harbor:`를 생략해 `fish/harbor_mackerel`처럼 쓴다. 한 번 정한 ID는 바꾸지 않는다.
- **재화:** 골드·물비늘은 Core 원장으로만 바뀐다. 진주(유료)는 Shop이 따로 관리한다.
- **등급:** common(일반) · uncommon(고급) · rare(희귀) · epic(영웅) · legendary(전설) · mythic(신화)
- **품질:** ★1~★5. 직업 단계별 상한이 있다 (1차 ★3, 2차 ★4, 3차 ★5).
- **문구:** 모든 문구는 `content/messages_ko.yml`의 키 + MiniMessage다.
- **수치:** 모든 게임 수치는 `content/balance.yml`에 있다. `/harbor reload balance`로 재시작 없이 다시 읽는다. 문서에 없던 값은 YAML 주석에 "제안값" 또는 "추가 제안"으로 적혀 있다.

## 아직 실제 서버에서 확인하지 않은 것

전체 빌드·단위 테스트·콘텐츠 검사는 통과하지만, 실제 Purpur 26.3 서버에서는 아직 돌려 보지 않았다. Phase 1 검증 항목:

- Mannequin NPC (스킨·자세 지원 범위)
- 보트 속도 보정, 해피 가스트 비행선 조종
- 컷씬 관전 카메라
- Interaction 히트박스 크기
- 리소스팩 모델 표시
