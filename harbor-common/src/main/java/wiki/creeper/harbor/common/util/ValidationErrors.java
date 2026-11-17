package wiki.creeper.harbor.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** 검증 오류 목록. 첫 오류에서 멈추지 않고 모두 모아서 한 번에 보여준다. */
public final class ValidationErrors {

    private final List<ValidationError> errors = new ArrayList<>();

    public void add(@Nullable SourcePos pos, String message) {
        errors.add(new ValidationError(pos, message));
    }

    public void addAll(ValidationErrors other) {
        errors.addAll(other.errors);
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    public int size() {
        return errors.size();
    }

    public List<ValidationError> list() {
        return Collections.unmodifiableList(errors);
    }

    /** 사람이 읽는 여러 줄 문자열. */
    public String format() {
        return errors.stream().map(ValidationError::toString).collect(Collectors.joining("\n"));
    }

    @Override
    public String toString() {
        return format();
    }
}
