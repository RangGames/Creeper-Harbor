# Harbor-Fishing

낚시의 핵심. 캐스팅 판정, 미니게임, 크기·품질, 미끼·내구도, 잡템, 신화 이벤트, 그림자 연출, 통발, 요리 버프, 매크로 방지를 맡는다. 다른 모듈은 `FishingService`·`TrapService`와 이벤트로 결과를 받는다.

## 주요 기능

- **캐스팅과 입질:** 지역(`regions.yml`) 안 물에 던지면 15~35초 뒤 입질이 온다. 미끼가 없으면 대기가 1.2배다. 입질 창은 20틱이고 핑이 높으면 보정된다.
- **어종 판정:** 지역·시간·날씨·달·세부 구역·미끼·직업 단계 조건을 만족하는 어종 중에서 등급 가중치로 고른다.
  - 기본 가중치: 일반 60 · 고급 25 · 희귀 10 · 영웅 4. 그 지역에 없는 등급은 빼고 다시 100%로 맞춘다.
  - 희귀 이상 보정 합계는 +60%가 상한이다.
  - **전설:** 기본 0.4%. 천장(pity) 카운터가 150회를 넘으면 회당 +0.02%씩 올라 최대 2.5%.
  - **신화:** 해상 이벤트 중에만 나온다 (캐스팅당 1%, 이벤트당 서버 마릿수 상한).
- **미니게임:** Shift를 누르고 있으면 감기, 떼면 풀기다. 텐션 막대를 안전 구간에 두고 물고기 체력을 깎는다.
  - 물고기가 ◀◀/▶▶ 쪽으로 도망치면 A/D를 같은 쪽으로 누르고 있어야 제 속도로 감긴다.
  - 등급이 높을수록 체력·텐션 상승·패턴 수가 늘어난다. 패턴은 돌진·잠수·점프·지침이다.
  - 텐션이 85%를 넘으면 화면 가장자리 경고가 뜬다. 줄 강도 1당 텐션 한계가 +8%다.
- **크기와 품질:** 크기는 어종의 `size_cm` 범위에서 정한다. 품질 ★1~★5는 백분위 경계(★2 40%, ★3 70%, ★4 90%, ★5 98%)로 정하고, 직업 단계 상한을 넘으면 깎인다. 상한 때문에 깎이면 "놓친 대어" 안내가 나온다.
- **미끼·장비 내구도:** 루어 사용 횟수, 낚싯대 내구도가 있다. 망가지기 직전이면 수리를 안내한다. 미끼를 바꾸면 걸린 물고기가 달아난다.
- **잡템:** 낚시 중 함께 건지는 아이템 (`fishing/junk.yml`).
- **포획 연출:** 등급별 프리셋(`fishing/presets.yml`)으로 입질·포획 때 화면 어둡게·타이틀·흔들림·빛기둥(등급 색)·폭죽·결과 Dialog·주변 알림·퍼덕임을 낸다. 대기 중에는 등급에 맞는 물고기 그림자를 던진 사람에게만 보여 준다 (어종 리그가 없으면 그림자 리그).
  - 캐스팅: 날아가는 찌를 따라 낚싯줄 궤적(dust), 착수 물보라. 챔질 순간 손 스윙(26.3 swing 애니메이션, `hook_swing`).
  - 대기: 전설 이상은 찌 주변이 어두워지고 낮은 진동음이 2초마다 난다 (`wait`, 본인만).
  - 입질: 찌 위에 등급 색 "!" Text Display가 입질 창 동안 뜬다 (본인만, `mark`·`mark_color`). 전설 이상은 본인 화면에만 리소스팩 post effect(`post_effect`, 26.3 Player#postEffects)와 카메라 오버레이(`camera_overlay`, 실제 투구는 그대로 두고 그 플레이어에게만 머리 아이템을 보냄)가 `effect_ticks` 동안 걸린다.
  - 챔질 스윙 길이는 낚싯대 아이템 스탯 `swing_ticks`가 있으면 그 값 (무거운 낚싯대일수록 길게).
  - 파이팅: 텐션을 `rod_bend_steps` 단계로 나눠 손에 든 낚싯대의 custom_model_data float 0(0~1)에 넣는다. 리소스팩 낚싯대 모델이 range_dispatch로 휘는 모델을 고른다. 끝나면 원래 값으로 되돌린다 (Boss 텐션 세션도 같음).
  - 측정(`measure`): 일반~고급 액션바, 희귀~영웅 타이틀, 전설~신화는 계측 숫자가 공중에 올라가며 세어진 뒤 타이틀 (주변 16블록에도 보임). 결과 Dialog는 측정이 끝난 뒤 뜬다.
  - 결과 카드: 크기·★·첫 포획, 전설 이상 첫 포획은 어종 설명(`lore`)까지 보여 주고 "도감 보기(/도감) / 자랑하기(이 서버 전체 채팅, 30초 쿨다운) / 닫기" 버튼이 있다.
  - 어종 아이템에는 custom_model_data float 0 = 어종 크기 범위 안 비율(0~1), float 1 = ★ 수가 들어간다 (Core `createFish`). 큰 개체를 더 크게 그리는 리소스팩 모델용.
- **전설·신화 전체 공지:** 전설·신화를 낚으면 서버 전체에 알린다.
- **통발:** 지역의 통발 구역 부표 자리에 설치한다.
  - 미끼를 넣어 두면 8시간 주기로 물고기가 들어온다 (주기 2회까지 쌓임).
  - 설치 개수 기본 2개, 생산 계열 2·3차에 +1씩 최대 4개.
  - 슬롯 확장은 슬롯당 2,000G × 통발 단계이고, 통발당 3칸까지다.
  - 하루 수익 상한: 수동 낚시 시간당 골드 × 40%(생산 2차 50%, 3차 60%).
  - 7일 동안 손대지 않으면 자동 회수한다. 심해 통발은 생산 캡스톤 "심해 통발"이 있어야 쓴다.
- **요리 버프:** 요리를 먹으면 일정 시간 효과가 이어진다. 요리 효과는 하나만 유지되고, 새로 먹으면 바뀐다. 연회 요리는 16블록 안 사람들과 함께 받는다.
- **매크로 방지:** 자동 낚시가 의심되면 "물고기를 고르세요" 확인 화면을 띄운다. 통과하지 못하면 5분 동안 낚시를 막는다.
- **해상 이벤트 효과:** World가 일정을 돌리는 이벤트(유령선·물고기 대이동·첫 물고기의 귀환 등) 동안 신화 어종 출현과 어종 가중치 배율을 적용한다.

## 플레이어 명령

| 명령 | 권한 | 설명 |
|---|---|---|
| `/낚시 설정` | (없음) | 방향 입력(A·D 이동 키 ↔ 시점 회전), 포획 결과 화면 켬/끔(영웅 이상), 연출 강도(최소·보통·최대) |
| `/낚시 요약` | (없음) | 오늘의 어획: 낚은 마릿수·최고 등급·최대어 (날짜 기준 `fishing.daily_zone`, 서버를 옮겨도 이어짐). G 키 빠른 메뉴에도 있다 |
| `/통발` | (없음) | 내 통발 목록 (지역·위치·다음 주기·미끼·보관 수·자동 회수까지 남은 시간) |

통발 자체는 설치한 통발을 우클릭해 연다 (미끼 넣기·모두 꺼내기·슬롯 확장·철거).

## 관리자 명령

`/harbor fishing …`은 `harbor.admin.fishing`이 필요하다.

| 명령 | 설명 |
|---|---|
| `/harbor fishing roll <플레이어> [횟수]` | 그 플레이어 조건으로 판정만 반복해 등급 분포 보기 (기본 1,000회) |
| `/harbor fishing simulate <지역>` | 지역 시뮬레이션 (등급 분포, 전설 첫 포획 평균 횟수) |
| `/harbor fishing pity <플레이어> <어종> [set <값>]` | 천장 카운터 보기·설정 |
| `/harbor fishing session <플레이어>` | 진행 중인 낚시 상태 (어종·텐션·체력·패턴) |
| `/harbor fishing force <플레이어> <어종>` | 다음 판정을 그 어종으로 고정 |
| `/harbor fishing macro <플레이어> [reset]` | 매크로 의심 점수 보기·초기화 |
| `/harbor fishing stats` | 최근 24시간 등급별 미니게임 시도·성공률·평균 시간 |
| `/harbor fishing trap list <플레이어>` | 그 플레이어의 통발 |
| `/harbor fishing trap calc <id>` · `trap remove <id>` | 통발 산출 계산, 통발 지우기 |
| `/harbor fishing event list` · `start <프리셋>` · `stop <이벤트>` | 해상 이벤트 낚시 효과 시험 (`fishing/sea_events.yml`) |
| `/harbor fishing food <플레이어>` | 지금 걸린 요리 버프와 만료 시각 |

## 콘텐츠 파일

| 파일 | 내용 |
|---|---|
| `fishing/presets.yml` | 등급별 연출 프리셋. `sounds`(던짐·착수·챔질·감기 반복·텐션 경고·줄 끊김 등 단계 소리), `cast_trail`·`hook_swing`·`rod_bend_steps`, 등급별 `shadow_rig`·`shadow_scale`·`glow`·`mark`·`mark_color`·`measure`·`wait`, `bite`·`catch`의 `flags`(darkness title shake pillar firework dialog nearby flop)와 `sound`. `intensity`로 생략할 수 있는 항목에 trail mark tape ambience rod_bend가 추가됨 |
| `fishing/sea_events.yml` | 해상 이벤트의 낚시 효과 기준표. `id` `region` `duration_min` `fish_effects`(`type: mythic`의 `fish` `chance` `cap`, `type: species_weight`의 `fish` `mult`) |
| `fishing/junk.yml` | 잡템 표 |
| `fishing/food.yml` | 요리 버프 규칙 |

어종(`fish/*.yml`)·아이템(`items/*.yml`)·지역(`regions.yml`)은 Core 형식이다 ([core.md](core.md#콘텐츠-파일-core-소유)). 통발 부표 자리는 `regions.yml`의 `trap_zone`에 있다.

## 설정 값

`balance.yml` `fishing`:

| 키 | 내용 |
|---|---|
| `grade_weight` · `rare_bonus_cap` | 등급 가중치, 희귀 이상 보정 상한 |
| `legend` | `base_chance` `pity_start` `pity_step` `max_chance` |
| `mythic.event_chance` | 이벤트 중 신화 확률 |
| `bite_wait_s` · `no_bait_wait` · `bite_window_ticks` · `ping_comp_s` | 입질 대기·창·핑 보정 |
| `line_strength_step` | 줄 강도 1당 텐션 한계 증가 |
| `grade_exp` · `first_catch_mult` · `region_exp_mult` | 등급별 경험치, 첫 포획 배율, 지역 배율 |
| `minigame` | `release_rate` `reel_per_tick` `safe_low` `safe_width` `escape_ticks` `warn_tension`, 등급별 `hp` `tension_rise` `patterns` `pattern_interval_s` |
| `quality` | 품질 경계, `tier_cap` |
| `long_cast.distance` | 원투 기준 거리 |
| `trap` | `cycle_h` `min_cycle_h` `max_cycles` `bait_capacity` `base_count` `max_count` `revenue_cap` `hourly_gold` `slot_price` `max_extra_slots` `retrieve_days` `buoy_radius` `refund_bait_item` |
| `food.feast_radius` | 연회 요리 공유 거리 |

`stats` 절의 `rare_weight`, `bite_wait`, `reel_speed`, `line_strength`, `tension_safe_zone`, `size_luck`, `bait_save_chance`, `cast_distance`, `legend_chance` 등이 낚시 수치를 보정한다.

## 다른 모듈과의 연결

- **제공 서비스:** `FishingService`(포획 결과·세션·그림자·도감 제공자 등록), `TrapService`
  - `FishingService` 관리·조회 API: `sessionInfo`(상태·굴린 어종·입질 대기·미니게임 장력·지역·미끼), `forceNextFish`·`clearForcedFish`(= `/harbor fishing force`), `setPity`·`resetPity`·`resetMissedBig`(= `pity ... set`), `foodBuff`·`applyFood`·`clearFood`(요리 버프, 먹은 것과 같은 규칙)
  - `TrapService` 행동 API: `trap(id)`·`trapIdAt(위치)`, `place`(손으로 설치할 때와 같은 검사, 아이템을 가져가거나 무료), `harvest`·`addBait`·`remove`(통발 화면과 같은 흐름: 오프라인 계산 → DB 커밋 뒤 아이템, 서버 이동 중이면 우편), `adminRemove`. 결과는 `TrapAction`(결과 키·통발 ID·수량)
- **확장 지점:** `RegionKeyResolver`(World가 세부 구역 판정을 등록), `TreasureDropHook`(World 보물 지도 드롭), `CollectionProvider`(Collection이 첫 포획 여부를 알려 줌), `ShadowDriver`(Wildlife가 그림자 대신 야생 물고기를 움직임), `CatchPresentationOverride`(Shop 포획 연출 코스메틱)
- **이벤트:** `FishingCastEvent`, `FishBiteEvent`, `FishRollEvent`, `QualityRolledEvent`, `MinigameEndEvent`, `FishCaughtEvent`, `FishingSessionEndEvent`, `TrapHarvestEvent`
- **쓰는 것:** Jobs(경험치 지급, 품질 상한, 전직 첫 품질 보상), Season(시즌 훅: 통발 정리), Core 보정자
- **원장 사유:** `TRAP_SLOT_EXPAND`

## 주의 사항

- 미니게임 조작은 지금 Shift 감기 + A/D 방향이다. 기획 문서의 "낚싯대 consumable을 누르고 있기" 방식은 바닐라 낚싯대 동작과 충돌하는지 Phase 1에서 확인한 뒤 바꾼다. 탈것 위에서는 자동으로 시점 회전 방식이다.
- 입질 대기·텐션 상승·통발 상한 등 다수 값이 제안값이다 (시간당 약 80마리 목표로 CBT에서 조정).
