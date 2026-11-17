package wiki.creeper.harbor.core.api;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;

/** 콘텐츠 레지스트리. 스냅샷은 변경 불가라 어느 스레드에서 읽어도 된다. */
public interface ContentService {

    /** 지금 적용된 스냅샷. 리로드되면 바뀌므로 오래 들고 있지 말고 쓸 때마다 부른다. */
    ContentSnapshot snapshot();

    Path root();

    /**
     * 다시 읽는다. 읽기·검사는 가상 스레드에서 하고, 오류가 없을 때만 메인 스레드에서 스냅샷을 바꾸고 ContentReloadedEvent를 발생시킨다.
     *
     * @param types 다시 읽을 형식 이름 (비우면 전체)
     * @param apply false면 검사만 한다 (/harbor validate)
     */
    CompletableFuture<Result<ContentSnapshot, ValidationErrors>> reload(Set<String> types, boolean apply);
}
