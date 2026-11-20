# Harbor-Boss

보스전 전체를 맡는다. 보스 정의, 조우 수명, 경기장(field·instance·world), 보스 리그·부위 판정, 패턴 예고, 페이즈 상태 기계, 최후의 줄다리기 QTE, 파티(Core `PartyService` 구현), 기여도, 전리품, 최단 기록, 월드 보스, 레이드 인스턴스가 여기 있다.

## 주요 기능

- **보스 유형:**

  | 유형 | 인원당 체력 증가 | 제한 시간 | 주간 보상 횟수 | 클리어 골드 |
  |---|---|---|---|---|
  | `intro` 입문 | 60% | 6분 | 5 | 300 |
  | `sea` 해역 | 50% | 8분 | 3 | 800 |
  | `dimension` 차원 | 45% | 12분 | 2 | 1500 |
  | `raid` 레이드 | 40% | 20분 | 1 | 3000 |
  | `world` 월드 | – | 30분 | 1 | 2000 |

  콘텐츠에 있는 보스: 늪지의 주인 '그놈'(intro), 난파선 수호 곰치(sea), 해구의 군주(sea, 스토리), 군주의 분노(raid), 녹빛 폭뢰어(world, 클라이맥스).
- **경기장:**
  - `field`: 본 서버 필드의 칸(slot)에서 연다.
  - `instance`: 인스턴스 서버의 경기장 칸에 배정받아 파티가 옮겨 간다.
  - `world`: 월드 보스.
- **목표:** `capture`면 보스 어종을 실제로 낚는다 (품질 보너스). calm 보스는 방류 전용 어종(`release_only`)을 쓴다.
- **전투 흐름:**
  1. 파티 대기(30초, 역할 선택)
  2. 등장 연출
  3. **걸기**(hook): 텐션 안전 구간을 유지해 게이지를 채운다
  4. **반격**(counter): 약점 노출 주기마다 부위 피해가 3배. 갑옷 부위는 0.2배
  5. **최후의 줄다리기**(final_tug): 체력 20% 아래에서 QTE

  패턴은 예고 모양(line·circle·cone)으로 미리 보여 준다. 핑이 150ms 이상인 파티원이 있으면 예고가 0.2초 늘어난다.
- **역할:** 고르지 않으면 직업 계열 추천 역할이 배정된다.

  | 역할 | 하는 일 | 추천 계열 |
  |---|---|---|
  | 앵커 `anchor` | 낚싯대로 보스를 붙잡는다 (텐션) | 수집 |
  | 작살수 `harpooner` | 작살 투척·근접 찌르기 (피해) | 탐험 (계열 없으면 기본) |
  | 선장 `captain` | 조타륜에서 돌진 회피 | 탐험 |
  | 정비사 `mechanic` | 선체 파손 지점 수리 | 생산 |
  | 보급관 `quartermaster` | 버프 요리 배포, 전리품 보너스 | 경제 |

- **기여도와 보상:** 피해 30% · 텐션 30% · 수리 20% · 회피 20%로 점수를 낸다.
  - 상위 20%는 재료를 1개 더 받는다.
  - 점수가 최고점의 5% 미만이고 활동 시간이 30% 미만이면 보상이 없다.
  - 주간 횟수를 넘긴 클리어는 골드 20%만 받는다.
  - 받지 못한 보상은 `/보스 보상`으로 받는다.
- **이탈:** 퇴장해도 60초 동안 자리가 유지된다. 전원이 60초 이탈하면 실패다. 물에 빠지면 5초 뒤 갑판으로 돌아온다.
- **최단 기록:** 보스별 클리어 시간 순위가 있다. 명예의 전당(Season)에 제출된다.
- **월드 보스:** 서버 전체가 참여한다. 유효 참여 최소 10명(최근 60초 기여 기준), 반경 64블록이다. 진행을 Redis로 모든 서버에 방송하고 저장해서, 서버가 재시작해도 10분 안이면 이어 간다. 클라이맥스(Quest 서버 플래그 `climax_start`) 때 녹빛 폭뢰어가 시작되고, 클리어하면 서버 플래그 `climax_done`이 선다.
- **파티:** 보스전용 파티(최대 인원은 보스 정의). 초대는 60초 동안 유효하다. 채팅을 `!`로 시작하면 파티원에게만 간다 (다른 서버에 있어도 됨).

## 플레이어 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/보스 정보 <보스>` | `harbor.boss` | 보스 정보 |
| `/보스 기록 <보스> [전체]` | `harbor.boss` | 최단 기록 (이번 시즌 / 전체) |
| `/보스 보상` | `harbor.boss` | 받지 못한 보상 받기 |
| `/보스 입장 <보스>` | `harbor.boss` | 인스턴스 보스 입장 신청 |
| `/보스 역할 <역할>` | `harbor.boss` | 내 역할 고르기 |
| `/파티 만들기` · `수락` · `나가기` · `해산` | `harbor.party` | 파티 만들기·초대 수락·나가기·해산 |
| `/파티 초대 <플레이어>` · `추방 <플레이어>` · `위임 <플레이어>` | `harbor.party` | 초대·추방·파티장 넘기기 |
| `/파티 역할 <역할> [플레이어]` | `harbor.party` | 내 역할 또는 파티원 역할 지정 |

## 관리자 명령

`/harbor boss …`는 `harbor.admin.boss`와 아래 하위 권한이 필요하다.

| 명령 | 권한 | 설명 |
|---|---|---|
| `/harbor boss start <보스> [플레이어]` | `harbor.admin.boss.start` | 보스전 시작 |
| `/harbor boss end <조우> clear\|fail\|abort` | `harbor.admin.boss.end` | 조우 강제 종료 |
| `/harbor boss world start <보스>` · `resume` · `stop` · `status` | `harbor.admin.boss.world` | 월드 보스 시작·이어 하기·중지·상태 |
| `/harbor boss pending <플레이어> [redeliver]` | `harbor.admin.boss.pending` | 받지 못한 보상 보기·다시 보내기 |
| `/harbor boss debug <조우>` · `/harbor boss list` | `harbor.admin.boss.debug` | 조우 상태, 진행 중 조우 목록 |

## 콘텐츠 파일

`bosses/intro.yml` · `sea.yml` · `raid.yml` · `world.yml`

| 필드 | 내용 |
|---|---|
| `id` `name` `type` `goal` `region` | 기본 정보 (`goal: capture` 등) |
| `arena` | `mode`(field·instance·world) `world` `slots`(`center` `radius`) `deck`(`spawn` `boss` `wheel` `repair`) |
| `party` | `min` `max` `base` |
| `hp` | `base` (인원 보정은 유형 표) |
| `rig` · `parts` | 보스 리그와 부위 (`bone` `weakpoint` `armor`) |
| `hull` | 선체 `max` `repair_per_action` |
| `phases` | `id`(hook·counter·final_tug) `patterns` `hook_seconds` `weak_interval` `weak_seconds` `enter`(`hp_below`) |
| `patterns` | `id` `name` `animation` `telegraph`(`shape` `size` `length` `angle` `at`) `effect`(`hull_damage` `knockback` `debuff` `tension`) `cooldown` `weight` `charge` |
| `fish` | 보스 어종 (`fish/boss.yml`) |

보스 리그는 `rigs/boss.yml`·`rigs/boss_sea.yml`에 있다. 리그의 뼈 이름과 부위가 맞는지 검사한다.

## 설정 값

`balance.yml` `boss`:

| 키 | 내용 |
|---|---|
| `types` | 유형별 `per_player_rate` `time_limit` `weekly` `gold` `telegraph_ticks` `pattern_gap_ticks` `anchors_min` `tension_mult` |
| `latency` | 핑 보정 |
| `prepare_seconds` · `intro_seconds` | 대기·등장 연출 |
| `hook` · `anchor` · `counter` | 걸기 게이지, 앵커 텐션 안전 구간·감기 피해, 약점 노출 주기 |
| `damage` | 부위 배율, 작살 피해·찌르기 |
| `qte` | `rounds` `inputs` `window_seconds` `pass` `win` `lose` `recover` |
| `contribution` | 가중치, 상위 비율, 무보상 기준, 보급관 버프 시간 |
| `loot` | 주간 초과 골드 비율, 보급관 보너스, 보스 어종 품질 보너스 |
| `leave` | 이탈 자리 유지, 전원 이탈 실패, 낙수 복귀 |
| `limits` | 동시 조우 상한 (`field` 6, `instance` 4) |
| `party.invite_seconds` · `items.repair_kit` | 초대 유효 시간, 정비사 수리 키트 |
| `world` | 월드 보스 `min_participants` `active_seconds` `radius` `max_anchors` `resume_minutes` `climax_boss` 등 |
| `instance` | 경기장 칸 수·간격, 배정·도착 대기, 재접속 TTL |

## 다른 모듈과의 연결

- **제공 서비스:** `BossService`, `BossRecordService`, `WorldBossService`, Core `PartyService` 구현
- **이벤트:** `BossEncounterStartEvent`, `BossEncounterEndEvent`, `BossPhaseChangeEvent`, `WorldBossProgressEvent`
- **쓰는 것:** Fishing(텐션 세션, 보스 어종 포획), Season(주간 제한, 명예의 전당 제출, 칭호), Core 리그(보스·부위 판정)
- **선택 의존:** Npc(대화 액션 `boss:enter`, 첫 처치·월드 보스 평판), Shop(보스전 중 펫 숨김. Shop이 Boss 이벤트를 듣기 때문에 Shop이 뒤에 켜진다)
- **Quest:** 보스 목표, 클라이맥스 시작 플래그
- **원장 사유:** `BOSS_REWARD`

## 주의 사항

- Phase 3 보스 4종과 해저 유적 던전은 아직 콘텐츠에 없다.
- Interaction 히트박스 크기(부위 판정)는 실제 서버에서 확인하지 않았다.
- 유형 표는 기획 문서 값이고, 나머지 수치와 경기장 좌표는 제안값이다.
