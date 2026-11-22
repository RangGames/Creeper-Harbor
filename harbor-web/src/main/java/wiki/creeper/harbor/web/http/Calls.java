package wiki.creeper.harbor.web.http;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.db.SqlFunction;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Main-thread-only reads and DB reads from REST handlers (virtual threads). 503 if they take too
 * long.
 */
public final class Calls {

    private static final long TIMEOUT_SECONDS = 5;

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;

    public Calls(HarborApi api, Tasks tasks, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
    }

    public HarborApi api() {
        return api;
    }

    /** Reads on the main thread (module caches, services). */
    public <T> T main(Supplier<T> read) throws Router.ApiException {
        return await(CompletableFuture.supplyAsync(read, tasks.main()));
    }

    public <T> T db(SqlFunction<T> query) throws Router.ApiException {
        return await(api.database().async(query));
    }

    public <T> T await(CompletableFuture<T> future) throws Router.ApiException {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new Router.ApiException(503, "timeout", "서버가 바빠 답하지 못했습니다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Router.ApiException(503, "interrupted", "중단되었습니다");
        } catch (ExecutionException e) {
            // Log the cause (SQL error text etc.) on the server only; never put it in the response
            log.warn("REST 읽기 실패", e.getCause());
            throw new Router.ApiException(500, "internal", "처리하지 못했습니다");
        }
    }
}
