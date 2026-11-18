package wiki.creeper.harbor.season.api;

/**
 * 시즌 상태 (season_meta.state, docs 20 11.3). 상태 전이는 기대 상태를 조건으로 건 UPDATE로만 한다.
 *
 * <pre>PLANNED → ACTIVE → TRANSITIONING → ARCHIVED</pre>
 */
public enum SeasonPhase {
    /** seasons.yml에 정의만 되어 있고 아직 열리지 않음. */
    PLANNED,
    /** 진행 중. */
    ACTIVE,
    /** 시즌 전환 중 (FREEZE ~ OPEN). 이 상태에서는 일반 플레이어 접속을 막는다. */
    TRANSITIONING,
    /** 끝나고 아카이브됨. */
    ARCHIVED;

    /** DB 값을 읽는다. 모르는 값이면 PLANNED. */
    public static SeasonPhase parse(String raw) {
        for (SeasonPhase phase : values()) {
            if (phase.name().equalsIgnoreCase(raw)) {
                return phase;
            }
        }
        return PLANNED;
    }
}
