package wiki.creeper.harbor.season.api;

import wiki.creeper.harbor.common.db.Database;

/** 시즌 전환 훅에 넘기는 정보. 훅은 가상 스레드에서 불리며 블로킹해도 된다. 이때 서버에는 일반 플레이어가 없다 (FREEZE 이후). */
public interface SeasonHookContext {

    /** 전환 ID (UUID). 참조 ID를 만들 때 쓰면 다시 실행해도 같은 값이 나온다. */
    String transitionId();

    /** 끝나는 시즌 n. */
    int fromSeason();

    /** 열리는 시즌 n+1. */
    int toSeason();

    /** 아카이브 스키마 이름 {@code archive_s<n>}. ARCHIVE 단계 이후 읽을 수 있다. */
    default String archiveSchema() {
        return "archive_s" + fromSeason();
    }

    /** true면 dry-run: 아무것도 쓰지 말고 {@link #report}로 할 일만 알린다. */
    boolean dryRun();

    Database database();

    /** 전환 일지(season_transition_log)와 관리자 화면에 남길 한 줄. */
    void report(String message);
}
