package wiki.creeper.harbor.common.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.SourcePos;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * YAML 콘텐츠 → 메모리 스냅샷.
 *
 * <ol>
 *   <li>파일을 모두 읽는다 (SnakeYAML 노드 단위, 줄 번호 유지)
 *   <li>레코드로 바꾼다 (필수 필드·타입·범위·ID 형식 검사)
 *   <li>참조를 검사한다 (어종의 region이 regions.yml에 있는지 등)
 *   <li>추가 검사({@link ContentCheck})를 실행한다
 * </ol>
 *
 * 오류가 하나도 없을 때만 새 스냅샷을 돌려준다. 이 클래스는 서버 API를 쓰지 않아서 harbor-tools(CI)에서도 같은 코드로 검사한다.
 * 스레드 안전하지 않으므로 등록은 시작할 때 한 스레드에서 한다.
 */
public final class ContentLoader {

    private final Map<String, ContentType<?>> types = new LinkedHashMap<>();
    private final Map<String, List<ContentCheck<?>>> checks = new HashMap<>();

    public void register(ContentType<?> type) {
        if (types.containsKey(type.name())) {
            throw new IllegalArgumentException("콘텐츠 형식 '" + type.name() + "'가 이미 등록되어 있습니다");
        }
        types.put(type.name(), type);
    }

    public <T> void addCheck(ContentType<T> type, ContentCheck<T> check) {
        checks.computeIfAbsent(type.name(), k -> new ArrayList<>()).add(check);
    }

    public Collection<ContentType<?>> types() {
        return List.copyOf(types.values());
    }

    public Optional<ContentType<?>> type(String name) {
        return Optional.ofNullable(types.get(name));
    }

    /** 등록된 모든 형식을 읽는다. */
    public Result<ContentSnapshot, ValidationErrors> load(Path root) {
        return reload(root, types.keySet(), ContentSnapshot.empty());
    }

    /**
     * 일부 형식만 다시 읽고, 나머지는 이전 스냅샷 값을 그대로 쓴다. 참조 검사는 합친 결과 전체로 한다.
     *
     * @param only 다시 읽을 형식 이름들
     */
    public Result<ContentSnapshot, ValidationErrors> reload(
            Path root, Set<String> only, ContentSnapshot previous) {
        ValidationErrors errors = new ValidationErrors();
        if (!Files.isDirectory(root)) {
            errors.add(null, "콘텐츠 폴더가 없습니다: " + root.toAbsolutePath());
            return Result.err(errors);
        }
        List<String> files;
        try {
            files = listFiles(root);
        } catch (IOException e) {
            errors.add(null, "콘텐츠 폴더를 읽지 못했습니다: " + e.getMessage());
            return Result.err(errors);
        }

        Map<String, Map<HarborId, Object>> entries = new HashMap<>();
        Map<String, Object> singles = new HashMap<>();
        Map<String, Map<HarborId, SourcePos>> positions = new HashMap<>();
        Map<String, SourcePos> singlePositions = new HashMap<>();
        ParseContext ctx = new ParseContext();

        for (ContentType<?> type : types.values()) {
            if (!only.contains(type.name())) {
                copyPrevious(type, previous, entries, singles);
                continue;
            }
            switch (type) {
                case ContentType.Entries<?> e ->
                        parseEntries(root, files, e, ctx, errors, entries, positions);
                case ContentType.Single<?> s ->
                        parseSingle(root, files, s, ctx, errors, singles, singlePositions);
            }
        }

        // 3단계: 참조 검사 (대상 형식이 등록된 경우만)
        for (ParseContext.Reference ref : ctx.references()) {
            if (!types.containsKey(ref.targetType())) {
                continue;
            }
            Map<HarborId, Object> target = entries.get(ref.targetType());
            if (target == null || !target.containsKey(ref.id())) {
                errors.add(ref.pos(), ref.targetType() + " '" + ref.id() + "'를 찾을 수 없습니다");
            }
        }

        ContentSnapshot snapshot = new ContentSnapshot(entries, singles);

        // 4단계: 추가 검사 (다시 읽은 형식만)
        if (errors.isEmpty()) {
            runChecks(only, snapshot, positions, singlePositions, errors);
        }
        return errors.isEmpty() ? Result.ok(snapshot) : Result.err(errors);
    }

    // ------------------------------------------------------------------ 내부

    private static List<String> listFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> p.endsWith(".yml") || p.endsWith(".yaml"))
                    .sorted()
                    .toList();
        }
    }

    private static List<String> matching(List<String> files, List<String> globs) {
        List<Pattern> patterns = globs.stream().map(Glob::compile).toList();
        return files.stream().filter(f -> patterns.stream().anyMatch(p -> p.matcher(f).matches())).toList();
    }

    private static void copyPrevious(
            ContentType<?> type,
            ContentSnapshot previous,
            Map<String, Map<HarborId, Object>> entries,
            Map<String, Object> singles) {
        Map<HarborId, Object> oldEntries = previous.rawEntries().get(type.name());
        if (oldEntries != null) {
            entries.put(type.name(), new TreeMap<>(oldEntries));
        }
        Object oldSingle = previous.rawSingles().get(type.name());
        if (oldSingle != null) {
            singles.put(type.name(), oldSingle);
        }
    }

    private static <T> void parseEntries(
            Path root,
            List<String> files,
            ContentType.Entries<T> type,
            ParseContext ctx,
            ValidationErrors errors,
            Map<String, Map<HarborId, Object>> entries,
            Map<String, Map<HarborId, SourcePos>> positions) {
        Map<HarborId, Object> result = new TreeMap<>();
        Map<HarborId, SourcePos> pos = new HashMap<>();
        for (String file : matching(files, type.files())) {
            Optional<YamlNode> root0 = YamlNode.read(root.resolve(file), file, errors);
            if (root0.isEmpty()) {
                continue;
            }
            YamlNode doc = root0.get();
            if (!doc.isList()) {
                doc.error("파일 최상위는 항목 목록(- id: ...)이어야 합니다");
                continue;
            }
            for (YamlNode item : doc.asList()) {
                int before = errors.size();
                Optional<HarborId> id = item.require("id").flatMap(n -> n.asId(type.idPrefix()));
                T value = type.parser().parse(item, ctx);
                if (id.isEmpty() || errors.size() > before) {
                    continue;
                }
                HarborId parsedId = type.idOf().apply(value);
                SourcePos previous = pos.get(parsedId);
                if (previous != null) {
                    item.error("ID '" + parsedId + "'가 중복됩니다 (먼저 정의된 곳: " + previous + ")");
                    continue;
                }
                result.put(parsedId, value);
                pos.put(parsedId, item.pos());
            }
        }
        entries.put(type.name(), result);
        positions.put(type.name(), pos);
    }

    private static <T> void parseSingle(
            Path root,
            List<String> files,
            ContentType.Single<T> type,
            ParseContext ctx,
            ValidationErrors errors,
            Map<String, Object> singles,
            Map<String, SourcePos> singlePositions) {
        if (!files.contains(type.file())) {
            if (type.required()) {
                errors.add(new SourcePos(type.file(), 1, 1), "필수 파일이 없습니다");
                return;
            }
            Optional<YamlNode> empty =
                    YamlNode.parse(type.file(), new java.io.StringReader("{}"), errors);
            empty.ifPresent(n -> singles.put(type.name(), type.parser().parse(n, ctx)));
            singlePositions.put(type.name(), new SourcePos(type.file(), 1, 1));
            return;
        }
        Optional<YamlNode> doc = YamlNode.read(root.resolve(type.file()), type.file(), errors);
        if (doc.isEmpty()) {
            return;
        }
        int before = errors.size();
        T value = type.parser().parse(doc.get(), ctx);
        if (errors.size() == before) {
            singles.put(type.name(), value);
            singlePositions.put(type.name(), doc.get().pos());
        }
    }

    private void runChecks(
            Set<String> only,
            ContentSnapshot snapshot,
            Map<String, Map<HarborId, SourcePos>> positions,
            Map<String, SourcePos> singlePositions,
            ValidationErrors errors) {
        for (ContentType<?> type : types.values()) {
            if (!only.contains(type.name())) {
                continue;
            }
            List<ContentCheck<?>> typeChecks = checks.getOrDefault(type.name(), List.of());
            if (typeChecks.isEmpty()) {
                continue;
            }
            switch (type) {
                case ContentType.Entries<?> e -> {
                    Map<HarborId, SourcePos> pos = positions.getOrDefault(e.name(), Map.of());
                    for (Map.Entry<HarborId, Object> entry :
                            snapshot.rawEntries().getOrDefault(e.name(), Map.of()).entrySet()) {
                        SourcePos at = pos.getOrDefault(entry.getKey(), new SourcePos(e.name(), 1, 1));
                        runChecks(typeChecks, entry.getValue(), snapshot, at, errors);
                    }
                }
                case ContentType.Single<?> s -> {
                    Object value = snapshot.rawSingles().get(s.name());
                    if (value != null) {
                        SourcePos at = singlePositions.getOrDefault(s.name(), new SourcePos(s.file(), 1, 1));
                        runChecks(typeChecks, value, snapshot, at, errors);
                    }
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void runChecks(
            List<ContentCheck<?>> typeChecks,
            Object value,
            ContentSnapshot snapshot,
            SourcePos at,
            ValidationErrors errors) {
        ContentCheck.Context context =
                new ContentCheck.Context() {
                    @Override
                    public ContentSnapshot snapshot() {
                        return snapshot;
                    }

                    @Override
                    public SourcePos pos() {
                        return at;
                    }

                    @Override
                    public void error(String message) {
                        errors.add(at, message);
                    }
                };
        for (ContentCheck check : typeChecks) {
            check.check(value, context);
        }
    }
}
