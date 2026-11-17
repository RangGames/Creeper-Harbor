package wiki.creeper.harbor.common.util;

import org.jspecify.annotations.Nullable;

/** 검증 오류 한 건. 위치를 알 수 있으면 "fish/coral_isles.yml 42행 3열: 메시지" 형식으로 출력한다. */
public record ValidationError(@Nullable SourcePos pos, String message) {

    @Override
    public String toString() {
        return pos == null ? message : pos + ": " + message;
    }
}
