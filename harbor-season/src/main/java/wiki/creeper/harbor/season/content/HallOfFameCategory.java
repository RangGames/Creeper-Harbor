package wiki.creeper.harbor.season.content;

import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * Hall of fame category (season/hall_of_fame.yml, docs 20 11.3, 11.4).
 *
 * @param category category ID (lowercase letters, digits, underscores)
 * @param name display name (MiniMessage)
 * @param source source recomputed at finalization
 * @param rule ranking rule
 * @param ascending whether lower values rank higher (kill time)
 * @param count number of people for first_n (1 for top1, 0 = unlimited for all)
 * @param level target level for level_first
 * @param title title granted at finalization (null if none). If it contains {@code <subject>}, that
 *     is replaced with the subject name
 * @param unit value unit for display (cm, seconds ...)
 */
public record HallOfFameCategory(
        String category,
        String name,
        Source source,
        Rule rule,
        boolean ascending,
        int count,
        int level,
        @Nullable String title,
        String unit) {

    /** Source. */
    public enum Source implements Keyed {
        /**
         * Collection records (top per species across all leagues). Collection submits; finalized
         * from archive_s<n>.records.
         */
        RECORDS,
        /**
         * Boss boss_records (fastest kill per boss). Boss submits; finalized from boss_records rows
         * for season n.
         */
        BOSS_RECORDS,
        /**
         * First to reach a season level. Computed from the time of the LEVEL_UP ledger entry
         * (reference ID lvl:<season>:<uuid>:<level>).
         */
        LEVEL_FIRST,
        /**
         * First to complete the season codex. Computed from the last first-catch time in
         * codex_season.
         */
        CODEX_FIRST,
        /** Finalizes submitted provisional records as-is (climax participation etc.). */
        SUBMITTED;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** Ranking rule. */
    public enum Rule implements Keyed {
        /** One top player per subject. */
        TOP1,
        /** The first n to achieve it. */
        FIRST_N,
        /** Everyone who submitted (proposed: climax participant list). */
        ALL;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** Title ID for a subject. null if there is no title. */
    public @Nullable TitleId titleFor(String subjectName) {
        String t = title;
        if (t == null) {
            return null;
        }
        return TitleId.tryOf(t.replace("<subject>", subjectName)).orElse(null);
    }
}
