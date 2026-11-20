# Harbor-Wildlife

바다가 살아 보이게 하는 야생 물고기 모듈이다. 바닐라 몹 AI 대신 자체 조향(가상 개체)으로 계산 스레드에서 움직이고, 보이는 개체만 Core 리그로 그린다.

- 출현 판정에는 영향을 주지 않는다. 판정은 Fishing만 한다.
- 연결은 Wildlife → Fishing 한 방향이다.
- DB·Redis를 쓰지 않는다. 개체는 저장하지 않고 서버마다 따로 돈다.

## 주요 기능

- **자연 스폰:** 플레이어 주변 16~40블록 고리에 무리를 만든다.
  - 후보 어종은 어종 YAML의 지역·시간·날씨·달·세부 구역 조건을 그대로 따른다. 그래서 야생에서 보이는 어종은 지금 낚이는 어종이다.
  - 전설·신화는 자연 스폰하지 않는다 (연출용 스크립트 스폰 전용).
  - 몸길이 1블록 미만은 3~6마리, 그 이상은 1~2마리가 무리를 짓는다.
  - 조개류는 바닥에 청크당 2개 이하로 둔다.
  - 모든 플레이어에게서 56블록 넘게 멀어지면 사라진다.
- **움직임:** 템플릿(fish·ceph·ray·seahorse·jelly·clam)과 매질(water·lava·air·floor)별로 순항·회전·기울기를 계산한다.
  - 성향(active·normal·lazy)에 따라 1초마다 고유 동작이나 쉬기를 굴린다.
  - 고유 동작 예: 돌진·먹물·점프·순간 이동·일광욕.
- **반응:** 플레이어가 다가오면 놀라 달아난다 (위협 반경). 찌가 떨어지면 성향 확률로 다가온다.
- **물고기 대이동:** 이벤트 중에는 밀도가 높아진다.
- **표시 예산:** 플레이어당 표시 마릿수·부품 수 상한과 서버 전체 상한이 있다. 거리별 갱신 빈도(LOD)를 나누고, 반영이 밀리면 자동 감속한다. 연출 강도 설정(`/낚시 설정`)에 따라 부품 예산을 줄인다.

## 플레이어 명령

없다.

## 관리자 명령

`/harbor wildlife …`는 `harbor.admin.wildlife`와 `harbor.admin.wildlife.wild`가 필요하다.

| 명령 | 설명 |
|---|---|
| `/harbor wildlife spawn <어종> [수량] [동작]` | 시선 앞 4블록에 소환 |
| `/harbor wildlife clear [반경]` | 주변 또는 전체 제거 |
| `/harbor wildlife debug` | 개체 위에 상태·성향·위상 표시, 조향 벡터 파티클 |
| `/harbor wildlife stats` | 개체 수, 플레이어별 표시 부품 수, 계산 시간, 반영 지연 |
| `/harbor wildlife budget <플레이어>` | 그 플레이어의 표시 목록과 예산 사용량 |

## 콘텐츠 파일

| 파일 | 내용 | 주요 필드 |
|---|---|---|
| `wildlife/species.yml` | 어종별 야생 이동 값 (어종 하나에 항목 하나) | `id`(wild/…) `fish_id` `template` `medium` `special` `group` `cruise_speed` `turn_rate` `depth` `school` `spawn_weight` `threat_radius` `rig` `scale` `enabled` |
| `wildlife/behaviours.yml` | 성향·동작 | `groups`(`special_pct` `idle_pct` `idle_sec` `speed_base` `threat_radius` `bait_chance`), `actions`(`group` `speed_delta` `reps` `impulse` `particle` `teleport` `threat`) |

- 비운 값은 자동으로 채운다. 성향은 고유 동작의 group, 순항 속도는 몸길이 × 2(0.6~2.2블록/초), 무리 크기는 몸길이 기준이다.
- 리그 선택 순서: `rig` → 어종 리그 `rig/fish/<모델 이름>` → `rig/wild/<템플릿>`. 어종 크기(`size_cm` 가운데 값)에 맞춰 그린다.
- `special`은 어종 리그의 동작 이름과 같아야 재생된다. 리그에 그 동작이 없으면 속도·순간 이동·파티클만 적용한다.
- 고유 동작이 `behaviours.yml`에 있는지, 어종당 항목이 하나인지 검사한다.

## 설정 값

`balance.yml` `wildlife`:

| 키 | 내용 |
|---|---|
| `budget` | `target_per_player` `max_shown` `max_parts` `global_max` `schools_per_player_s` `spawn_per_tick` `intensity` |
| `spawn` | `ring_min` `ring_max` `school_gap` `min_depth` `air_clearance` `despawn` `small_school` `large_school` `floor_per_chunk` `floor_blocks` `boost` |
| `lod` | `near` `show` `keep`, 거리별 전송 간격, `hidden_grace_s` |
| `sim` | `period_ms`(10Hz) `apply_budget_ms` `lag_cycles` `lag_shown_scale` |
| `move` · `school` · `threat` · `bait` · `air` · `grid` · `light` | 조향·무리·위협 감지·미끼 반응·공중·공간 격자·밝기 |

## 다른 모듈과의 연결

- **제공 서비스:** `WildlifeService` (스크립트 스폰 `ScriptedBehaviour` 등)
- **이벤트:** `WildFishSpawnEvent`, `WildFishStartleEvent`
- **쓰는 것:**
  - Fishing: `FishingCastEvent`, 그림자 대신 야생 물고기를 움직이는 `ShadowDriver`·`claimShadow`
  - World: 지역·세부 구역, 물고기 대이동 이벤트
  - Core 리그

## 주의 사항

- 예산·LOD·위협 감지 값은 제안값이다. CBT 뒤 Phase 3 부하 테스트로 조정한다.
- `behaviours.yml`은 데이터팩 시제품 값을 손으로 옮긴 것이다. 변환기는 아직 없다.
