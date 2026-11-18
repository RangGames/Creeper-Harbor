package wiki.creeper.harbor.season.api;

import java.util.Optional;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.TypedId;

/**
 * 칭호 ID (titles.yml, 추가 제안). 예: {@code harbor:title/codex_25}.
 *
 * <p>{@link #of(String)}는 접두사를 생략한 이름도 받는다: {@code codex_25}, {@code title/codex_25}, {@code
 * harbor:title/codex_25}는 모두 같은 칭호다 (Collection의 지역 완성 칭호 {@code codex_region/coral_isles}도 그대로 넘기면
 * 된다).
 */
public record TitleId(HarborId id) implements TypedId {

    public static final String PREFIX = "title/";

    public TitleId {
        TypedId.requirePrefix(id, PREFIX);
    }

    /** 형식이 틀리면 IllegalArgumentException. */
    public static TitleId of(String raw) {
        HarborId id = HarborId.parse(raw);
        if (!id.hasPrefix(PREFIX)) {
            id = new HarborId(id.namespace(), PREFIX + id.path());
        }
        return new TitleId(id);
    }

    public static Optional<TitleId> tryOf(String raw) {
        try {
            return Optional.of(of(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code title/} 뒤의 이름 (명령어 표시용). */
    public String shortName() {
        return id.path().substring(PREFIX.length());
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
