package wiki.creeper.harbor.quest.content;

import org.jspecify.annotations.Nullable;

/**
 * When a cutscene plays (proposed cutscene_at): accept · complete · objective:&lt;key&gt;. This
 * cutscene does not block progress. A cutscene that blocks progress is a {@code type: cutscene}
 * objective.
 *
 * @param objectiveKey the objective key when objective:&lt;key&gt;
 */
public record CutsceneAt(Kind kind, @Nullable String objectiveKey) {

    public enum Kind {
        ACCEPT,
        COMPLETE,
        OBJECTIVE
    }

    public static final CutsceneAt COMPLETE = new CutsceneAt(Kind.COMPLETE, null);
}
