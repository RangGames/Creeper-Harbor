package wiki.creeper.harbor.common.util;

import java.util.function.Function;

/** 성공 값 또는 실패 사유. 예외 대신 호출자가 실패를 반드시 다루게 할 때 쓴다. */
public sealed interface Result<T, E> permits Result.Ok, Result.Err {

    record Ok<T, E>(T value) implements Result<T, E> {}

    record Err<T, E>(E error) implements Result<T, E> {}

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    default boolean isOk() {
        return this instanceof Ok;
    }

    default <U> Result<U, E> map(Function<? super T, ? extends U> mapper) {
        return switch (this) {
            case Ok<T, E>(T value) -> Result.ok(mapper.apply(value));
            case Err<T, E>(E error) -> Result.err(error);
        };
    }

    /** 성공 값을 꺼낸다. 실패면 IllegalStateException. 테스트·확실한 경로에서만 쓴다. */
    default T orThrow() {
        return switch (this) {
            case Ok<T, E>(T value) -> value;
            case Err<T, E>(E error) -> throw new IllegalStateException("Result is Err: " + error);
        };
    }
}
