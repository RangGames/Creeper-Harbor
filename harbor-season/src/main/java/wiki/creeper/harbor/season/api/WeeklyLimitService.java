package wiki.creeper.harbor.season.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * 주간 횟수 제한 저장소 (weekly_counter, docs 20 0.1·11.4). 보스 주간 보상({@code boss:<boss_id>})과 레시피 주간
 * 제작({@code craft:<recipe_id>})을 하나로 센다. 계정 기준이며 지금 시즌·주차({@link SeasonClock#week()}) 행에 쓴다.
 *
 * <p>모든 쓰기는 즉시 트랜잭션이고, 서버 여러 대에서 동시에 불러도 한도를 넘지 않는다 (조건부 UPDATE 한 문장으로 판정).
 *
 * <pre>{@code
 * // Boss: CLEARED 판정 직후
 * limits.tryConsume(uuid, "boss:" + boss.id().name(), 3).thenAccept(r -> {
 *     if (r.consumed()) giveLoot(); else giveParticipationOnly();
 * });
 *
 * // Economy: 제작 비용 차감과 한 트랜잭션으로
 * currency.atomic(entries, c -> {
 *     if (!limits.tryConsume(c, uuid, "craft:" + recipe.id().name(), recipe.weeklyLimit())) {
 *         throw new LedgerException(LedgerError.REJECTED, "주간 제작 한도");
 *     }
 * });
 * }</pre>
 */
public interface WeeklyLimitService {

    /** 키 형식: 소문자·숫자·밑줄과 {@code : / . -}, 1~96자. 예 {@code boss:swamp_catfish}. */
    Pattern KEY = Pattern.compile("[a-z0-9_:/.\\-]{1,96}");

    static String requireKey(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("주간 제한 키 형식이 틀렸습니다: " + key);
        }
        return key;
    }

    /** 보스 주간 보상 키. */
    static String bossKey(String bossName) {
        return requireKey("boss:" + bossName);
    }

    /** 레시피 주간 제작 키. */
    static String craftKey(String recipeName) {
        return requireKey("craft:" + recipeName);
    }

    /**
     * 한 번 쓴다. 이번 주 사용 횟수가 limit 미만이면 1 올리고 성공. 결과 future는 메인 스레드에서 완료된다.
     *
     * @param limit 주간 한도 (1 이상)
     */
    CompletableFuture<LimitResult> tryConsume(UUID player, String key, int limit);

    /** 남은 횟수와 사용 횟수만 읽는다. 결과 future는 메인 스레드에서 완료된다. */
    CompletableFuture<LimitResult> remaining(UUID player, String key, int limit);

    /**
     * 호출한 쪽의 트랜잭션 안에서 한 번 쓴다 (CurrencyService.atomic의 extra 등). 블로킹이므로 메인 스레드에서 부르지 않는다. 트랜잭션이 롤백되면
     * 횟수도 되돌아간다.
     *
     * @return 성공하면 true, 한도에 닿았으면 false (아무것도 바꾸지 않음)
     */
    boolean tryConsume(Connection connection, UUID player, String key, int limit)
            throws SQLException;

    /**
     * 한 번 되돌린다 (추가 제안: 보상 트랜잭션이 실패해 횟수만 차감된 경우). 0 아래로는 내려가지 않는다. 결과 future는 메인 스레드에서 완료된다.
     *
     * @return 되돌렸으면 true
     */
    CompletableFuture<Boolean> refund(UUID player, String key);
}
