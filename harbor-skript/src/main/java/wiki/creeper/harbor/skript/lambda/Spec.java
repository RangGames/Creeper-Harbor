package wiki.creeper.harbor.skript.lambda;

import ch.njol.skript.lang.Expression;
import java.util.List;
import org.bukkit.event.Event;
import org.jspecify.annotations.Nullable;

/**
 * 람다 문법 하나의 정보 (등록할 때 정하고, Skript가 만드는 인스턴스마다 같이 쓴다). 모듈 클래스를 쓰는 람다는 그 모듈의 bridge 클래스 안에서만 만든다 (모듈이
 * 꺼져 있으면 그 클래스를 불러오지 않는다).
 */
public final class Spec {

    /** 식: 값 목록. 값이 없으면 빈 목록. */
    @FunctionalInterface
    public interface Getter<T> {
        List<T> get(Event event, Expression<?>[] exprs);
    }

    /** 조건. */
    @FunctionalInterface
    public interface Check {
        boolean test(Event event, Expression<?>[] exprs);
    }

    /** 효과. */
    @FunctionalInterface
    public interface Action {
        void run(Event event, Expression<?>[] exprs);
    }

    /** 이 이벤트 안에서만 쓸 수 있는 식이면 그 이벤트 클래스 (아니면 null). */
    final @Nullable Class<? extends Event> onlyIn;

    final String label;

    /** true면 첫 번째 인자(플레이어 목록 등)가 하나일 때만 단일 값. false면 늘 단일. */
    final boolean perFirstArg;

    private Spec(String label, boolean perFirstArg, @Nullable Class<? extends Event> onlyIn) {
        this.label = label;
        this.perFirstArg = perFirstArg;
        this.onlyIn = onlyIn;
    }

    /** 첫 인자(보통 %players%)마다 값 하나. */
    public static Spec of(String label) {
        return new Spec(label, true, null);
    }

    /** 늘 값 하나 (서버 전체 값, 또는 단일 인자). */
    public static Spec single(String label) {
        return new Spec(label, false, null);
    }

    /** 그 이벤트 안에서만 (event-값 대신 이름 있는 식). */
    public static Spec in(String label, Class<? extends Event> event) {
        return new Spec(label, false, event);
    }
}
