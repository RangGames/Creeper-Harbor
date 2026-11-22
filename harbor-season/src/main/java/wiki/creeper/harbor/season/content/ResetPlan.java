package wiki.creeper.harbor.season.content;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.season.api.TitleId;

/**
 * Season transition plan (season/reset_plan.yml, docs 20 11.3, 11.4). Season tables are handled
 * declaratively only; cleanup that needs code is done by each module's SeasonHook.
 *
 * @param tables per-table policies (processed in the order listed)
 * @param currencies currencies whose balances are zeroed with SEASON_RESET ledger entries at RESET
 *     (gold, mulbineul)
 * @param fishLedgerStates fish_ledger states whose season-n items are changed to ARCHIVED
 * @param openTitles titles granted at the OPEN step (CBT commemorative titles etc.)
 * @param gate first-login cleanup rules for the new season (SeasonGate)
 * @param transition transition engine operating values
 */
public record ResetPlan(
        List<TableRule> tables,
        Set<Currency> currencies,
        List<String> fishLedgerStates,
        List<OpenTitle> openTitles,
        Gate gate,
        TransitionSettings transition) {

    public static final ContentType.Single<ResetPlan> TYPE =
            new ContentType.Single<>(
                    "reset_plan", "season/reset_plan.yml", false, ResetPlan::parse);

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Pattern STATE = Pattern.compile("[A-Z_]{1,16}");

    public ResetPlan {
        tables = List.copyOf(tables);
        currencies = currencies.isEmpty() ? Set.of() : Set.copyOf(currencies);
        fishLedgerStates = List.copyOf(fishLedgerStates);
        openTitles = List.copyOf(openTitles);
    }

    /** Table scope. */
    public enum Scope implements Keyed {
        /** Table with a season column: only season-n rows are handled. */
        SEASON_COLUMN,
        /** Table without a season column: the whole table is handled (auction_listing etc.). */
        FULL;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** Table policy. */
    public enum Policy implements Keyed {
        /** Copies to the archive, then deletes from the source (keep_where rows are kept). */
        ARCHIVE_RESET,
        /** Copies to the archive only; the source is left as is. */
        ARCHIVE_KEEP,
        /** Left untouched. */
        KEEP,
        /** Copies to the archive; cleanup is done by that module's SeasonHook.onReset. */
        CUSTOM;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }

        public boolean archives() {
            return this != KEEP;
        }
    }

    /**
     * Plan for one table.
     *
     * @param column season column name (scope = season_column, default season)
     * @param keepWhere condition for rows not deleted by archive_reset (SQL WHERE fragment, e.g.
     *     {@code quest_id LIKE 'harbor:quest/main/%'})
     */
    public record TableRule(
            String table, Scope scope, Policy policy, String column, @Nullable String keepWhere) {}

    /**
     * Title grant at the OPEN step.
     *
     * @param fromSeason only when the ending season has this number (0 = every time)
     * @param who grant target: {@code season_players} = everyone who had a player_season row in the
     *     ending season
     */
    public record OpenTitle(int fromSeason, TitleId title, String who) {}

    /**
     * First-login cleanup for the new season (SeasonGate).
     *
     * @param keepVanilla whether to keep vanilla items that are not Harbor items
     * @param keepOldFish whether to keep fish items caught in past seasons
     * @param resetVanillaState whether to reset vanilla experience, effects, health and hunger
     * @param spawn starting location to move to (null = spawn of the first world)
     */
    public record Gate(
            boolean keepVanilla,
            boolean keepOldFish,
            boolean resetVanillaState,
            @Nullable Spawn spawn) {}

    /** Location. */
    public record Spawn(String world, double x, double y, double z, float yaw, float pitch) {}

    /**
     * Transition engine operating values (proposed value).
     *
     * @param noticeMinutes countdown notice times (minutes, descending)
     * @param flushTimeoutSeconds how long FREEZE waits for all servers to respond
     * @param batchRows chunk size for copying and deleting large tables (docs 20 11.6: 50,000 rows)
     * @param diskFactor PRECHECK free disk threshold (DB size × this value)
     * @param backupMarker marker file the ops script leaves after backup (relative to the plugin
     *     folder)
     * @param rowsPerSecond throughput used to compute the dry-run time estimate
     * @param maxMinutes target duration (dry-run warning threshold, docs 20 11.4: 60 minutes)
     */
    public record TransitionSettings(
            List<Integer> noticeMinutes,
            int flushTimeoutSeconds,
            int batchRows,
            double diskFactor,
            String backupMarker,
            int rowsPerSecond,
            int maxMinutes) {

        public TransitionSettings {
            noticeMinutes = List.copyOf(noticeMinutes);
        }

        public static TransitionSettings defaults() {
            return new TransitionSettings(
                    List.of(30, 10, 5, 1), 120, 50_000, 2.0, "season-backup.marker", 20_000, 60);
        }
    }

    public static ResetPlan empty() {
        return new ResetPlan(
                List.of(),
                Set.of(),
                List.of(),
                List.of(),
                new Gate(false, false, true, null),
                TransitionSettings.defaults());
    }

    public Optional<TableRule> rule(String table) {
        return tables.stream().filter(t -> t.table().equals(table)).findFirst();
    }

    static ResetPlan parse(YamlNode root, ParseContext ctx) {
        root.allowOnly(
                Set.of("tables", "currencies", "fish_ledger", "open_titles", "gate", "transition"));
        List<TableRule> tables = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (YamlNode node : root.list("tables")) {
            node.allowOnly(Set.of("table", "scope", "policy", "column", "keep_where"));
            String table = node.string("table");
            if (!NAME.matcher(table).matches()) {
                node.error("테이블 이름은 소문자·숫자·밑줄이어야 합니다: " + table);
            }
            if (!seen.add(table)) {
                node.error("테이블 " + table + "이(가) 계획에 두 번 있습니다");
            }
            Scope scope =
                    node.has("scope")
                            ? node.enumValue("scope", Scope.class).orElse(Scope.SEASON_COLUMN)
                            : Scope.SEASON_COLUMN;
            Policy policy = node.enumValue("policy", Policy.class).orElse(Policy.KEEP);
            String column = node.string("column", "season");
            if (!NAME.matcher(column).matches()) {
                node.error("column은 소문자·숫자·밑줄이어야 합니다: " + column);
            }
            @Nullable String keepWhere = node.optString("keep_where").orElse(null);
            if (keepWhere != null) {
                if (policy != Policy.ARCHIVE_RESET) {
                    node.error("keep_where는 policy: archive_reset에만 쓸 수 있습니다");
                }
                if (!safeFragment(keepWhere)) {
                    node.error("keep_where에는 ; 와 주석(--, /*, #)을 쓸 수 없습니다");
                }
            }
            tables.add(new TableRule(table, scope, policy, column, keepWhere));
        }

        Set<Currency> currencies = EnumSet.noneOf(Currency.class);
        for (YamlNode c : root.list("currencies")) {
            c.asEnum(Currency.class)
                    .ifPresent(
                            currency -> {
                                if (!currency.isGameCurrency()) {
                                    c.error("진주는 Shop이 관리하므로 시즌 초기화 재화가 아닙니다");
                                } else {
                                    currencies.add(currency);
                                }
                            });
        }

        List<String> states = new ArrayList<>();
        Optional<YamlNode> ledger = root.get("fish_ledger");
        if (ledger.isPresent()) {
            ledger.get().allowOnly(Set.of("archive_states"));
            for (YamlNode s : ledger.get().list("archive_states")) {
                String state = s.asString().toUpperCase(Locale.ROOT);
                if (!STATE.matcher(state).matches()) {
                    s.error("상태 이름 형식이 틀렸습니다: " + state);
                }
                states.add(state);
            }
        }

        List<OpenTitle> openTitles = new ArrayList<>();
        for (YamlNode node : root.list("open_titles")) {
            node.allowOnly(Set.of("from_season", "title", "who"));
            String raw = node.string("title");
            Optional<TitleId> title = TitleId.tryOf(raw);
            if (title.isEmpty()) {
                node.error("칭호 ID 형식이 틀렸습니다: " + raw);
                continue;
            }
            ctx.ref(TitleDef.TYPE.name(), title.get().id(), node.pos());
            String who = node.string("who", "season_players");
            if (!who.equals("season_players")) {
                node.error("who는 season_players만 쓸 수 있습니다");
            }
            openTitles.add(
                    new OpenTitle(node.integer("from_season", 0, 0, 1000), title.get(), who));
        }

        Gate gate = new Gate(false, false, true, null);
        Optional<YamlNode> gateNode = root.get("gate");
        if (gateNode.isPresent()) {
            YamlNode g = gateNode.get();
            g.allowOnly(Set.of("keep_vanilla", "keep_old_fish", "reset_vanilla_state", "spawn"));
            Spawn spawn = null;
            Optional<YamlNode> s = g.get("spawn");
            if (s.isPresent()) {
                YamlNode sp = s.get();
                sp.allowOnly(Set.of("world", "x", "y", "z", "yaw", "pitch"));
                spawn =
                        new Spawn(
                                sp.string("world"),
                                sp.number("x"),
                                sp.number("y"),
                                sp.number("z"),
                                (float) sp.number("yaw", 0),
                                (float) sp.number("pitch", 0));
            }
            gate =
                    new Gate(
                            g.bool("keep_vanilla", false),
                            g.bool("keep_old_fish", false),
                            g.bool("reset_vanilla_state", true),
                            spawn);
        }

        TransitionSettings settings = TransitionSettings.defaults();
        Optional<YamlNode> t = root.get("transition");
        if (t.isPresent()) {
            YamlNode tn = t.get();
            tn.allowOnly(
                    Set.of(
                            "notice_minutes",
                            "flush_timeout_s",
                            "batch_rows",
                            "disk_factor",
                            "backup_marker",
                            "rows_per_second",
                            "max_minutes"));
            List<Integer> notice = new ArrayList<>();
            for (YamlNode m : tn.list("notice_minutes")) {
                int minutes = m.asInt();
                if (minutes < 1 || minutes > 180) {
                    m.error("공지 시점은 1~180분이어야 합니다");
                }
                notice.add(minutes);
            }
            notice.sort((a, b) -> Integer.compare(b, a));
            String marker = tn.string("backup_marker", settings.backupMarker());
            if (marker.contains("..") || marker.startsWith("/") || marker.contains("\\")) {
                tn.error("backup_marker는 플러그인 폴더 안의 상대 경로여야 합니다");
            }
            settings =
                    new TransitionSettings(
                            notice.isEmpty() ? settings.noticeMinutes() : notice,
                            tn.integer("flush_timeout_s", settings.flushTimeoutSeconds(), 10, 3600),
                            tn.integer("batch_rows", settings.batchRows(), 100, 1_000_000),
                            tn.number("disk_factor", settings.diskFactor()),
                            marker,
                            tn.integer("rows_per_second", settings.rowsPerSecond(), 1, 10_000_000),
                            tn.integer("max_minutes", settings.maxMinutes(), 1, 1440));
        }
        return new ResetPlan(tables, currencies, states, openTitles, gate, settings);
    }

    /**
     * Whether keep_where has no statement separators or comments (so a content authoring mistake
     * cannot run another statement).
     */
    static boolean safeFragment(String sql) {
        return !sql.contains(";")
                && !sql.contains("--")
                && !sql.contains("/*")
                && !sql.contains("#")
                && !sql.isBlank();
    }
}
