package wiki.creeper.harbor.economy.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 원장 기록이 있으면 CurrencyService.atomic, 없으면 (등록비 0%, 골드 없는 요리) 같은 규칙의 DB 트랜잭션. 추가 SQL이 {@link
 * LedgerException}을 던지면 롤백하고 그 사유를 돌려준다. 결과는 메인 스레드에서 완료된다.
 */
final class Tx {

    private Tx() {}

    static CompletableFuture<Result<List<Long>, LedgerError>> run(
            HarborApi api, Tasks tasks, List<LedgerEntry> entries, @Nullable SqlConsumer extra) {
        if (!entries.isEmpty()) {
            return api.currency().atomic(entries, extra);
        }
        return api.database()
                .async(
                        c -> {
                            if (extra != null) {
                                extra.accept(c);
                            }
                            return Result.<List<Long>, LedgerError>ok(List.of());
                        })
                .handle(
                        (result, error) -> {
                            if (error == null) {
                                return result;
                            }
                            Throwable cause =
                                    error instanceof CompletionException && error.getCause() != null
                                            ? error.getCause()
                                            : error;
                            if (cause instanceof LedgerException le) {
                                return Result.<List<Long>, LedgerError>err(le.error());
                            }
                            System.getLogger("Harbor/Economy")
                                    .log(System.Logger.Level.ERROR, "트랜잭션 실패", cause);
                            return Result.<List<Long>, LedgerError>err(LedgerError.DATABASE);
                        })
                .thenApplyAsync(r -> r, tasks.main());
    }

    /** 결과의 실패 사유 (성공이면 null). */
    static @Nullable LedgerError error(Result<?, LedgerError> result) {
        return result instanceof Result.Err<?, LedgerError>(LedgerError e) ? e : null;
    }
}
