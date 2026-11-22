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

/** REST 처리기(가상 스레드)에서 메인 스레드 전용 값 읽기와 DB 읽기. 오래 걸리면 503. */
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

    /** 메인 스레드에서 읽는다 (모듈 캐시·서비스). */
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
            // 원인(SQL 오류 문구 등)은 서버 로그에만 남기고 응답에는 싣지 않는다
            log.warn("REST 읽기 실패", e.getCause());
            throw new Router.ApiException(500, "internal", "처리하지 못했습니다");
        }
    }
}
