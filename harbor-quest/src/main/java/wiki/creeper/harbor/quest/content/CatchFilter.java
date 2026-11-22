package wiki.creeper.harbor.quest.content;

import wiki.creeper.harbor.common.model.Keyed;

/** Catch source a catch objective counts (proposed objectives[].source). */
public enum CatchFilter implements Keyed {
    /** Fishing rod catches only (including guaranteed boss catches). Default. */
    ROD,
    /** Also counts trap harvests. */
    ANY;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
