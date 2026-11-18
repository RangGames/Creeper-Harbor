package wiki.creeper.harbor.season.transition;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.season.api.SeasonHook;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * 전환 엔진이 서버(또는 harbor-tools 리허설 CLI)에 맡기는 일. 엔진은 Paper API를 모르고 이 인터페이스로만 바깥과 이야기한다. 모든 메서드는 가상
 * 스레드에서 불리며 블로킹해도 된다.
 */
public interface TransitionEnvironment {

    /** 이 서버가 지금 돌리는 시즌 (harbor-infra.yml season.id). */
    int currentSeason();

    /** PRECHECK: 다음 시즌 콘텐츠(seasons.yml·content_dir) 검사 오류. */
    List<String> checkContent(int nextSeason);

    /** PRECHECK: 다음 시즌 리소스팩 URL을 받아 SHA-1이 맞는지. 오류 목록. */
    List<String> checkResourcePack(int nextSeason);

    /** DB 서버 디스크 여유 (바이트). 알 수 없으면 빈 값 (경고만 남긴다). */
    OptionalLong freeDiskBytes();

    boolean titleExists(TitleId title);

    /** 다음 시즌 시작·종료 시각 (season_meta 행이 없을 때 만들기 위해). */
    SeasonWindow window(int season);

    /** 시작·종료 시각 (epoch 초). */
    record SeasonWindow(long startsAt, long endsAt) {}

    /** 전 서버 공지 (harbor:announce). 메시지 키와 자리표시자. */
    void announce(String messageKey, Map<String, String> placeholders);

    /** 관리자에게 진행 상황 (접속 중인 harbor.admin.season.transition 보유자와 콘솔). */
    void progress(String messageKey, Map<String, String> placeholders);

    /** FREEZE: 모든 서버가 플레이어를 내보내고 지연 쓰기 큐를 비울 때까지 기다린다. */
    FreezeResult freeze(String transitionId, int season, Duration timeout)
            throws InterruptedException;

    /** 결과. missing = 응답이 없거나 실패한 서버. */
    record FreezeResult(boolean ok, List<String> servers, List<String> missing) {}

    /** 롤백 A 뒤 접속 차단을 푼다. */
    void thaw(String transitionId, int season);

    /** season_meta가 바뀌었다고 모든 서버에 알린다. */
    void metaChanged(int season);

    /**
     * RESET: 시즌 n 잔액을 SEASON_RESET 원장 기록(참조 ID season_reset:&lt;n&gt;:&lt;uuid&gt;)으로 0으로 만든다. 반드시
     * CurrencyService(Common LedgerWriter)를 거친다. 다시 불러도 같은 결과여야 한다.
     *
     * @return 처리한 원장 기록 수
     */
    long resetBalances(int season, Set<Currency> currencies) throws Exception;

    /** SNAPSHOT: 최종 랭킹을 ranking_snapshot에 남긴다. */
    int snapshotRankings(Connection c, int season, String archiveSchema) throws SQLException;

    /** SNAPSHOT: 명예의 전당 확정 + 칭호 지급. 분류별 행 수. */
    Map<String, Integer> finalizeHallOfFame(Connection c, int season, String archiveSchema)
            throws SQLException;

    /** 등록된 SeasonHook (등록 순서). */
    List<SeasonHook> hooks();

    /** CONTENT: 다음 시즌 콘텐츠·리소스팩으로 바꿀 준비를 하고 확인한다. 오류 목록 (비면 성공). */
    List<String> switchContent(int nextSeason);

    /** 플러그인 폴더 기준 경로 (백업 표식 파일). */
    Path dataPath(String relative);
}
