package wiki.creeper.harbor.quest.content;

import org.jspecify.annotations.Nullable;

/**
 * 컷씬 재생 시점 (추가 제안 cutscene_at): accept · complete · objective:&lt;key&gt;. 이 컷씬은 진행을 막지 않는다. 진행을 막는
 * 컷씬은 {@code type: cutscene} 목표로 둔다.
 *
 * @param objectiveKey objective:&lt;key&gt;일 때 그 목표 키
 */
public record CutsceneAt(Kind kind, @Nullable String objectiveKey) {

    public enum Kind {
        ACCEPT,
        COMPLETE,
        OBJECTIVE
    }

    public static final CutsceneAt COMPLETE = new CutsceneAt(Kind.COMPLETE, null);
}
