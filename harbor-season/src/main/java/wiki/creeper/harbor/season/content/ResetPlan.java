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
 * 시즌 전환 계획 (season/reset_plan.yml, docs 20 11.3·11.4). 시즌 테이블을 선언으로만 다루고, 코드가 필요한 정리는 각 모듈의
 * SeasonHook이 맡는다.
 *
 * @param tables 테이블별 정책 (적은 순서대로 처리)
 * @param currencies RESET 때 잔액을 SEASON_RESET 원장 기록으로 0으로 만들 재화 (골드·물비늘)
 * @param fishLedgerStates fish_ledger에서 시즌 n 아이템을 ARCHIVED로 바꿀 상태들
 * @param openTitles OPEN 단계에서 지급할 칭호 (CBT 기념 칭호 등)
 * @param gate 새 시즌 첫 접속 정리 규칙 (SeasonGate)
 * @param transition 전환 엔진 운영값
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

    /** 테이블 범위. */
    public enum Scope implements Keyed {
        /** season 열이 있는 테이블: 시즌 n 행만 다룬다. */
        SEASON_COLUMN,
        /** season 열이 없는 테이블: 전체를 다룬다 (auction_listing 등). */
        FULL;

        @Override
        public String key() {
            return Keyed.keyOf(this);
        }
    }

    /** 테이블 정책. */
    public enum Policy implements Keyed {
        /** 아카이브에 복사한 뒤 원본에서 지운다 (keep_where 행은 남김). */
        ARCHIVE_RESET,
        /** 아카이브에 복사만 하고 원본은 그대로. */
        ARCHIVE_KEEP,
        /** 손대지 않는다. */
        KEEP,
        /** 아카이브에 복사하고, 정리는 그 모듈의 SeasonHook.onReset이 한다. */
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
     * 테이블 하나의 계획.
     *
     * @param column 시즌 열 이름 (scope = season_column, 기본 season)
     * @param keepWhere archive_reset에서 지우지 않을 행 조건 (SQL WHERE 조각, 예 {@code quest_id LIKE
     *     'harbor:quest/main/%'})
     */
    public record TableRule(
            String table, Scope scope, Policy policy, String column, @Nullable String keepWhere) {}

    /**
     * OPEN 단계 칭호 지급.
     *
     * @param fromSeason 끝나는 시즌이 이 번호일 때만 (0이면 매번)
     * @param who 지급 대상: {@code season_players} = 끝나는 시즌에 player_season 행이 있던 사람 전원
     */
    public record OpenTitle(int fromSeason, TitleId title, String who) {}

    /**
     * 새 시즌 첫 접속 정리 (SeasonGate).
     *
     * @param keepVanilla Harbor 아이템이 아닌 바닐라 아이템을 남길지
     * @param keepOldFish 지난 시즌에 잡은 어종 아이템을 남길지
     * @param resetVanillaState 바닐라 경험치·효과·체력·배고픔을 초기화할지
     * @param spawn 옮길 시작 지점 (null이면 첫 월드의 스폰)
     */
    public record Gate(
            boolean keepVanilla,
            boolean keepOldFish,
            boolean resetVanillaState,
            @Nullable Spawn spawn) {}

    /** 위치. */
    public record Spawn(String world, double x, double y, double z, float yaw, float pitch) {}

    /**
     * 전환 엔진 운영값 (제안값).
     *
     * @param noticeMinutes 카운트다운 공지 시점 (분, 큰 순)
     * @param flushTimeoutSeconds FREEZE에서 모든 서버 응답을 기다리는 시간
     * @param batchRows 대형 테이블 분할 복사·삭제 단위 (docs 20 11.6: 5만 행)
     * @param diskFactor PRECHECK 디스크 여유 기준 (DB 크기 × 이 값)
     * @param backupMarker 운영 스크립트가 백업 뒤 남기는 표식 파일 (플러그인 폴더 기준)
     * @param rowsPerSecond dry-run 예상 시간 계산용 처리 속도
     * @param maxMinutes 목표 소요 시간 (dry-run 경고 기준, docs 20 11.4: 60분)
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

    /** keep_where에 문장 구분자·주석이 없는지 (콘텐츠 작성 실수로 다른 문장이 실행되지 않게). */
    static boolean safeFragment(String sql) {
        return !sql.contains(";")
                && !sql.contains("--")
                && !sql.contains("/*")
                && !sql.contains("#")
                && !sql.isBlank();
    }
}
