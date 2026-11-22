# Harbor-Web

외부 연동 모듈이다 (선택). 게임 이벤트를 밖으로 내보내고, 웹훅과 읽기 전용 REST API를 연다. 다른 게임 모듈은 모두 선택 의존이라, 켜져 있는 모듈의 이벤트와 자료만 다룬다.

자세한 이벤트 type 목록, 웹훅 서명 형식, REST 경로는 [harbor-web/README.md](../harbor-web/README.md)에 있다.

## 주요 기능

- **이벤트 발행 (모든 서버):** 각 모듈의 이벤트(포획·퀘스트 완료·기록 경신·보스 클리어·해상 이벤트 등)를 공통 형식으로 바꿔 Redis `harbor:events`에 발행한다. 외부 서비스가 직접 구독해도 된다. 다른 플러그인은 Core `ExternalEvents`로 자기 이벤트를 더할 수 있다 ([core.md](core.md#다른-모듈과의-연결)).
- **웹훅 (한 서버):**
  - `discord` 형식: `messages_ko.yml`의 `web.discord.<type>` 문구로 보낸다. 문구가 없는 type은 보내지 않고, 멘션은 막는다.
  - `json` 형식: 이벤트 전체를 POST하고 HMAC-SHA256 서명 헤더를 붙인다.
  - 대상마다 받을 type과 data 조건(`where`)을 정한다.
  - 실패하면 재시도한다. 여러 서버에서 켜도 `harbor:lock:webhook` 잠금을 잡은 서버만 보낸다.
- **REST API (한 서버):** `Authorization: Bearer <토큰>`이 필요하다. 토큰마다 분당 요청 수를 제한한다. 플레이어 정보·도감·퀘스트·원장, 최근 이벤트, 어종 목록, 시세, 경매, 기록, 랭킹을 읽기 전용으로 준다. 기본 bind는 `127.0.0.1`이고, 앞에 HTTPS 리버스 프록시를 둔다.

## 플레이어 명령

없다.

## 관리자 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/harbor web status` | `harbor.admin.web` | 발행·웹훅·REST 상태 |
| `/harbor web test <대상>` | `harbor.admin.web` | 웹훅 대상에 시험 이벤트 보내기 |
| `/harbor web events` | `harbor.admin.web` | 최근 이벤트 10개 (REST 서버에서) |

## 콘텐츠 파일

없다. 디스코드 문구만 `messages_ko.yml`의 `web.discord.*`에 둔다.

## 설정 값

`plugins/Harbor-Web/config.yml` (웹훅 URL·토큰이 들어가므로 저장소에 넣지 않는다):

| 키 | 내용 |
|---|---|
| `events.publish` · `events.exclude` | 이벤트 발행 (기본 켬), 뺄 type |
| `webhooks.enabled` · `retries` · `queue_size` · `timeout_seconds` · `targets` | 웹훅 (대상별 `name` `url` `format` `secret` `events` `where`) |
| `http.enabled` · `bind` · `port` · `rate_limit_per_minute` · `recent_events` · `cors_origins` · `tokens` | REST API |

## 다른 모듈과의 연결

- Core `ExternalEvents` 서비스를 거쳐 발행한다.
- 모든 게임 모듈의 api 이벤트를 듣는다 (선택 의존).
- REST의 모듈 자료는 그 모듈의 캐시를 메인 스레드에서 읽는다. 플레이어 자료는 DB에서 읽기만 한다.

## 주의 사항

- 접속 중인 플레이어 자료는 지연 쓰기 때문에 몇 분 늦을 수 있다.
- REST는 5초 안에 답하지 못하면 503을 준다.

## 기술 명세

웹 팀용 상세 명세 (코드 기준):

- [harbor-web/docs/api.md](../harbor-web/docs/api.md): REST API 설명
- [harbor-web/docs/openapi.yaml](../harbor-web/docs/openapi.yaml): OpenAPI 3.1 명세
- [harbor-web/docs/events.md](../harbor-web/docs/events.md): 이벤트 형식·카탈로그, 웹훅 서명·재시도
