# harbor-tools 와 빌드 도구

서버 밖에서 쓰는 도구다. harbor-tools는 콘텐츠 검증 CLI이고, 서버 플러그인과 같은 로더 코드를 쓴다. 리소스팩 관련 작업은 루트 Gradle 작업이 맡는다.

명령은 Windows 기준(`gradlew.bat`)이다. macOS·Linux는 `./gradlew`.

## 작업 목록

| Gradle 작업 | 하는 일 | 결과 |
|---|---|---|
| `validateContent` | `content/` YAML 검사 | 오류가 있으면 실패 |
| `packIndex -Ppack=<팩 폴더 또는 zip>` | 리소스팩의 item_model·소리 목록 뽑기 | `content/resource_pack.yml` |
| `rigImport -Prigs=<팩 assets/harbor/rigs/fish·boss 폴더> [-PrigStep=<틱>]` | 리그 JSON(harbor_rig_v1) 변환 | `content/rigs/fish·boss/<이름>.yml` |
| `resourcePack -PpackDir=<팩 폴더> [-PpackMeta=<zip 또는 폴더>]` | 클라이언트 리소스팩 zip 만들기 | `build/resourcepack/<이름>.zip` + `.sha1` |
| `dist -Ppack=<zip>` 또는 `dist -PpackDir=<폴더>` | 플러그인 jar와 함께 팩·SHA-1 모으기 | `build/dist/resourcepack/` |

## validateContent

```bat
gradlew.bat validateContent
```

- 서버와 같은 로더로 저장소 `content/`를 검사한다. 디자이너가 올린 YAML의 오류를 서버에 넣기 전에 CI에서 거른다.
- Core와 모든 게임 모듈의 콘텐츠 형식(`XxxContent`)과, 서버 API가 필요 없는 교차 검사를 등록한다 (참조 ID 존재, 레벨 곡선, 퀘스트 선행 순환, 보물함 확률 합계, Tebex 패키지 번호 중복 등).
- 바닐라 아이템 이름처럼 서버 API가 있어야 하는 검사는 서버에서만 한다 (`/harbor validate`).
- 퀘스트 목표 유형은 기본 유형만 안다. 다른 모듈이 서버에서 등록하는 유형은 서버 검사에서 확인한다.
- 모듈을 추가하면 그 모듈의 `XxxContent`도 harbor-tools에 등록해야 한다.

## packIndex

```bat
gradlew.bat packIndex -Ppack=<리소스팩 폴더 또는 zip>
```

- 모델: `assets/<ns>/items/<경로>.json` → `<ns>:<경로>`
- 소리: `assets/<ns>/sounds.json`의 키 → `<ns>:<키>`
- 팩 루트에 `assets`가 없으면 한 단계 아래 폴더에서 찾는다.
- 만든 파일에는 팩의 로컬 경로를 적지 않는다.
- Core `ItemModels`는 이 목록에 있는 모델만 쓴다. 리소스팩 자동 빌드 정보(Redis `harbor:pack`)가 있으면 그쪽이 우선이다. `resource_pack.yml`은 손으로 고치지 않고, 팩을 바꾸면 다시 만든다.

## rigImport

```bat
gradlew.bat rigImport -Prigs=<리소스팩 assets/harbor/rigs/fish 폴더>
```

- 리소스팩의 리그 JSON(harbor_rig_v1)을 플러그인 리그 형식으로 바꾼다. id가 `harbor:fish/<이름>`이면 `content/rigs/fish/`, `harbor:boss/<이름>`이면 `content/rigs/boss/`에 쓴다.
- JSON에 있으면 그대로 옮기는 값: 리그 `default_animation`(없으면 swim)·`view_range`, 뼈 `hitbox`({width, height, offset}, 뼈 기준 상자 중심)·`glow_color`. 보스 판정 상자·약점 발광은 이 값으로 정한다.
- 원본 키프레임을 다시 계산하지 않는다. 원본의 `baked.frames`(20tps)를 부모 본 기준 지역 변환으로 풀어 고른 간격 샘플로 넣는다.
- 샘플 간격은 `balance.yml` `rig.fps`에서 정한다 (20 / fps틱). `-PrigStep`으로 바꿀 수 있다.
- 모델이 없는 본(root 등)은 빼고, 그 자식은 모델이 있는 가장 가까운 조상에 붙인다.
- `content/rigs/fish/*.yml`·`content/rigs/boss/*.yml`은 직접 고치지 말고 다시 변환한다. 변환한 분류 폴더의 기존 yml은 지운다.

## resourcePack

```bat
gradlew.bat resourcePack -PpackDir=<팩 폴더> [-PpackMeta=<pack.mcmeta가 든 zip 또는 폴더>]
```

- `assets`를 넣고, `pack.mcmeta`·`pack.png`는 `-PpackMeta` → 팩 폴더 순서로 찾는다. 없으면 기본 `pack.mcmeta`를 만든다 (형식 `-PpackFormat`, 기본 97.1).
- 서버용 리그 데이터(`*/rigs/**`)와 OS 부산물, `*_source.*`는 뺀다.
- 파일 순서·시각을 고정해서 같은 입력이면 같은 zip(같은 SHA-1)이 나온다.
- 이름은 `-PpackName`(기본 `Creeper-Harbor-RP`)이다.
- 만든 SHA-1은 lobby `harbor-infra.yml`의 `resource_packs`에 넣는다. 서버의 리소스팩 자동 빌드(`pack.build`)를 쓰면 이 작업 없이 서버가 직접 압축·업로드한다 ([core.md](core.md#리소스팩-파이프라인)).

## 직접 실행 (CLI)

harbor-tools는 일반 Java 애플리케이션이다.

```
harbor-tools validate <콘텐츠 폴더>
harbor-tools pack-index <리소스팩 폴더 또는 zip> <콘텐츠 폴더>
harbor-tools rig-import <리그 JSON 폴더> <콘텐츠 폴더> [샘플 간격 틱]
```

harbor-tools는 서버에 넣지 않는다 (`dist`에서 제외).
