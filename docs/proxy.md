# Harbor-Proxy

Velocity 프록시 플러그인. 접속 진입점(점검·제재·후원 등급·세션 잠금 확인), 본 서버 대기열, 서버·인스턴스 이동 지휘, 서버 간 채팅·공지 중계, 접속 기록·다계정 탐지, MOTD·서버 링크를 맡는다.

Common을 품고 있고 (DB 풀 5개, Redis 연결 1개) Paper API는 쓰지 않는다. 설정 폴더는 `plugins/harbor-proxy/`다.

## 주요 기능

- **로비 → 본 서버 대기열:** 처음 접속하면 로비에 들어간다. 리소스팩이 적용되면 출항 대기열에 자동으로 들어간다 (`queue.auto_join`). 끄면 로비에 머물고 `/출항`·NPC 대화·Skript로 출발한다.
- **우선 줄:** 후원 등급(Redis `harbor:donor`)과 `priority` UUID는 우선 줄에 선다. 입장 `normal_share`명 중 최소 1명은 일반 줄에서 들어간다.
- **재접속 유예:** 본 서버에서 끊긴 사람은 `grace_seconds`(기본 3분) 동안 자리가 유지된다.
- **TPS 보호:** 본 서버 TPS가 `tps_freeze` 아래면 수용 한도를 동결하고, `tps_resume` 이상이 1분 이어지면 푼다. 1초에 최대 `per_second`명만 들여보낸다.
- **서버 이동:** 백엔드의 이동 요청을 받아 세션 잠금 handoff와 함께 옮긴다. 승인되지 않은 방법(바닐라 `/server`)으로는 서버를 옮길 수 없다. Velocity 기본 `/server` 명령은 해제한다.
- **연결 끊김:** 본 서버와 연결이 끊기면 로비로 돌려보내고 자리를 유예한다.
- **인스턴스 배정:** 던전·레이드 같은 인스턴스 서버 목록(`instance_servers`)으로 보낸다.
- **점검:** 점검 중에는 `maintenance.bypass` UUID만 들어온다. 시작 전 카운트다운 공지 뒤 접속자를 정리한다. MOTD도 점검 문구로 바뀐다.
- **제재:** 밴·뮤트·킥. 접속 때 DB로 제재를 확인하고, 시간 안에 확인하지 못하거나 DB 오류면 접속을 거부한다.
- **다계정 탐지:** 최근 `alt.window_days`일 동안 같은 IP를 쓴 계정이 `alert_accounts`개 이상이면 운영진에게 알린다. 자동 차단은 없다. 공유 IP를 등록해 제외할 수 있다.
- **접속 기록:** 시각·서버·프로토콜·클라이언트 브랜드·IP 해시를 `retention_days`일 보관한다.
- **서버 간 채팅·귓속말:** 백엔드가 그린 채팅 줄을 모든 서버에 중계한다. 금칙어·도배(같은 문장 반복, 초당 줄 수)를 거른다.
- **하트비트 감시:** 서버 하트비트가 `heartbeat.alert_seconds` 이상 끊기면 운영진에게 알린다.
- **서버 링크:** 일시정지 메뉴에 웹사이트·디스코드 같은 링크를 띄운다 (`links`).

## 플레이어 명령

| 명령 | 별칭 | 권한 | 설명 |
|---|---|---|---|
| `/로비` | `/lobby` `/hub` | (없음) | 로비로 이동 |
| `/출항` | | (없음) | 로비에서 출항 대기열에 들어가기 (로비에서만) |
| `/대기열` | `/queue` | (없음) | 내 대기 순번과 예상 시간 |
| `/귓 <대상> <메시지>` | `/w` `/whisper` | (없음) | 귓속말 (다른 서버에 있어도 됨) |
| `/답장 <메시지>` | `/r` `/reply` | (없음) | 마지막 귓속말 상대에게 답장 |

## 관리자 명령

`/hp`는 아래 하위 명령 권한 중 하나라도 있어야 보인다.

| 명령 | 권한 | 설명 |
|---|---|---|
| `/hp maintenance on [분] [사유]` · `off` · `status` | `harbor.admin.proxy.maintenance` | 점검 시작(기본 10분 뒤)·해제·상태 |
| `/hp send <대상> <서버>` | `harbor.admin.proxy.send` | 플레이어 서버 이동 |
| `/hp queue list` · `move <대상> <순번>` · `pause` · `resume` · `cap <수>` | `harbor.admin.proxy.queue` | 대기열 보기·순번 변경·멈춤·재개·수용 한도 |
| (권한만) | `harbor.admin.proxy.queue.bypass` | 대기열 건너뛰기 |
| `/hp servers` | `harbor.admin.proxy.servers` | 서버별 역할·인원·TPS·하트비트·상태 |
| `/hp announce <메시지>` | `harbor.admin.proxy.announce` | 전체 공지 |
| `/hp ban <대상> [기간] [사유]` · `unban <대상>` | `harbor.admin.proxy.sanction` | 접속 제한. 기간 예: `30m` `12h` `7d` `2w` `perm` (생략하면 `sanction.default_ban`) |
| `/hp mute <대상> [기간] [사유]` · `unmute <대상>` | `harbor.admin.proxy.sanction` | 채팅 제한 (기간 생략하면 `sanction.default_mute`) |
| `/hp kick <대상> [사유]` | `harbor.admin.proxy.sanction` | 연결 끊기 |
| `/hp lookup <대상>` | `harbor.admin.proxy.lookup` | 접속 여부·후원 등급·최근 접속 기록·제재 내역 |
| `/hp alts <대상>` | `harbor.admin.proxy.lookup` | 같은 IP를 쓴 계정 |
| `/hp sharedip add\|remove <대상>` | `harbor.admin.proxy.lookup` | 그 사람의 최근 IP를 공유 IP로 등록·해제 |

## 설정 값

### `harbor-infra.yml`

`server.id: proxy`, `server.role: proxy`, `database`(기본 풀 5), `redis`, `season`. 백엔드와 같은 DB·Redis를 본다.

### `harbor-proxy.yml`

| 키 | 내용 |
|---|---|
| `lobby` | 로비 서버 이름 (잠금 없음) |
| `main_servers` | 본 서버와 수용 한도 (기본 150명) |
| `instance_servers` | 인스턴스 종류별 서버 목록 (`dungeon`, `raid`) |
| `queue` | `normal_share` `grace_seconds` `per_second` `tps_freeze` `tps_resume` `auto_join` |
| `priority` | 우선 줄 UUID (운영진·테스터) |
| `login` | `lookup_timeout_ms`(후원 등급·세션 잠금 조회 제한, 넘기면 기본값으로 진행), `sanction_timeout_ms`(넘기면 접속 거부) |
| `sanction` | 기간을 생략했을 때의 밴·뮤트 기간 |
| `alt` | `window_days`, `alert_accounts` |
| `join_log.retention_days` | 접속 기록 보관 기간 |
| `heartbeat.alert_seconds` | 하트비트 끊김 알림 |
| `links` | 일시정지 메뉴 서버 링크 (`type`: 바닐라 기본 이름, 또는 `label`: MiniMessage) |
| `maintenance` | `bypass` UUID, `fallback`(Redis가 없을 때 점검 상태로 볼지) |
| `ip_hash_secret` | 접속 기록 IP 해시용 비밀 키. **반드시 바꾼다** |
| `chat` | `repeat_limit` `lines_per_second` `block_seconds` `banned_words` |
| `messages` | 프록시 문구 (MOTD·제재·대기열·이동·채팅). 백엔드 `messages_ko.yml`과 별개 |

## 다른 모듈과의 연결

- 백엔드 Core와 Redis로 연결한다. 서버 이동 요청, 세션 잠금(`harbor:lock:<uuid>`), 하트비트, 채팅, 공지가 오간다.
- 네트워크 접속·퇴장을 모든 백엔드에 알린다 (`harbor:network`의 `NetworkJoin`·`NetworkQuit`). 백엔드 Core가 `NetworkJoinEvent`·`NetworkQuitEvent`로 바꾸고, Skript에서는 `on network join`·`on network quit`이다.
- 후원 등급은 Shop이 Redis `harbor:donor`에 쓰고 프록시가 읽는다 (Common `DonorTier`).
- Skript 애드온의 `harbor send player to sea`도 출항 대기열로 보낸다.

## 주의 사항

- 제재 확인이 `sanction_timeout_ms` 안에 끝나지 않거나 DB 오류면 접속을 거부한다. DB가 불안정하면 접속이 막힐 수 있다.
- Redis 장애 때는 복제 방지를 위해 서버 이동을 막는다.
- 대기열 수치(`normal_share`, `grace_seconds` 등)와 도배 기준은 제안값이다. CBT 지표를 보고 조정한다.
