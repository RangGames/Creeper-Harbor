package wiki.creeper.harbor.quest.api;

import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.plugin.Plugin;

/**
 * 목표 유형 등록 (docs 20 6.2). 모듈의 enable(콘텐츠 로드 전)에서 등록해야 콘텐츠 검사가 유형을 안다. 등록한 유형은 {@link
 * ObjectiveSignal}로 진행시킨다.
 */
public interface ObjectiveTypeRegistry {

    /** 콘텐츠 검사 단계에서 목표 하나를 확인한다. 틀리면 error로 알린다. */
    @FunctionalInterface
    interface Validator {
        void validate(ObjectiveSpec objective, Consumer<String> error);
    }

    /** 신호 대상이 목표 대상과 맞는지. */
    @FunctionalInterface
    interface Matcher {
        boolean matches(ObjectiveSpec objective, String signalTarget);
    }

    /**
     * 목표 하나의 요약 (다른 모듈에 넘기는 값).
     *
     * @param target 대상 표기 원문
     */
    record ObjectiveSpec(String quest, String key, String type, String target, int count) {}

    /** 같은 키가 이미 있으면 IllegalStateException. owner가 꺼지면 자동으로 지운다. */
    void register(Plugin owner, String key, Validator validator, Matcher matcher);

    /** 등록된 유형 (기본 유형 포함). */
    Set<String> types();
}
