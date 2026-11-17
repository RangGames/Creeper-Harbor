package wiki.creeper.harbor.common.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.SourcePos;

/** 파싱 중에 다른 콘텐츠를 가리키는 참조를 모은다. 모든 파일을 읽은 뒤 한꺼번에 검사한다. */
public final class ParseContext {

    /** 참조 한 건: 어떤 형식의 어떤 ID를 어디서 가리켰는지. */
    public record Reference(String targetType, HarborId id, SourcePos pos) {}

    private final List<Reference> references = new ArrayList<>();

    /**
     * 참조를 등록한다. 대상 형식이 로더에 등록되어 있지 않으면 (그 모듈이 아직 없으면) 검사를 건너뛴다.
     *
     * @param targetType 대상 형식 이름 (예: {@code region})
     */
    public void ref(String targetType, HarborId id, SourcePos pos) {
        references.add(new Reference(targetType, id, pos));
    }

    List<Reference> references() {
        return Collections.unmodifiableList(references);
    }
}
