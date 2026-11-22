package wiki.creeper.harbor.season.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Weekly count limit store (weekly_counter, docs 20 0.1, 11.4). Counts boss weekly rewards ({@code
 * boss:<boss_id>}) and recipe weekly crafts ({@code craft:<recipe_id>}) in one place. Per account,
 * written to the row for the current season and week ({@link SeasonClock#week()}).
 *
 * <p>All writes are immediate transactions and never exceed the limit even when called concurrently
 * from several servers (decided by a single conditional UPDATE).
 *
 * <pre>{@code
 * // Boss: right after the CLEARED decision
 * limits.tryConsume(uuid, "boss:" + boss.id().name(), 3).thenAccept(r -> {
 *     if (r.consumed()) giveLoot(); else giveParticipationOnly();
 * });
 *
 * // Economy: in one transaction with the crafting cost deduction
 * currency.atomic(entries, c -> {
 *     if (!limits.tryConsume(c, uuid, "craft:" + recipe.id().name(), recipe.weeklyLimit())) {
 *         throw new LedgerException(LedgerError.REJECTED, "weekly craft limit");
 *     }
 * });
 * }</pre>
 */
public interface WeeklyLimitService {

    /**
     * Key format: lowercase letters, digits, underscores and {@code : / . -}, 1-96 characters. E.g.
     * {@code boss:swamp_catfish}.
     */
    Pattern KEY = Pattern.compile("[a-z0-9_:/.\\-]{1,96}");

    static String requireKey(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("주간 제한 키 형식이 틀렸습니다: " + key);
        }
        return key;
    }

    /** Boss weekly reward key. */
    static String bossKey(String bossName) {
        return requireKey("boss:" + bossName);
    }

    /** Recipe weekly craft key. */
    static String craftKey(String recipeName) {
        return requireKey("craft:" + recipeName);
    }

    /**
     * Uses one. If this week's usage count is below limit, increments it and succeeds. The result
     * future completes on the main thread.
     *
     * @param limit weekly limit (1 or more)
     */
    CompletableFuture<LimitResult> tryConsume(UUID player, String key, int limit);

    /** Reads only the remaining and used counts. The result future completes on the main thread. */
    CompletableFuture<LimitResult> remaining(UUID player, String key, int limit);

    /**
     * Uses one inside the caller's transaction (CurrencyService.atomic extra etc.). Blocking, so do
     * not call it on the main thread. If the transaction rolls back, the count is reverted too.
     *
     * @return true on success, false if the limit was reached (nothing changed)
     */
    boolean tryConsume(Connection connection, UUID player, String key, int limit)
            throws SQLException;

    /**
     * Reverts one use (proposed: when the reward transaction failed and only the count was
     * deducted). Never goes below 0. The result future completes on the main thread.
     *
     * @return true if reverted
     */
    CompletableFuture<Boolean> refund(UUID player, String key);

    /**
     * Refunds one use in the given week. Pass the week the use was consumed in (fetch it with
     * {@link #currentWeek} right before consuming): if the week rolls over in between, {@link
     * #refund(UUID, String)} would refund the new week's count and allow one extra use.
     */
    CompletableFuture<Boolean> refund(UUID player, String key, int week);

    /** Current week (within this season, starting at 0). */
    int currentWeek();
}
