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
 * YAML content → in-memory snapshot.
 *
 * <ol>
 *   <li>Reads all files (as SnakeYAML nodes, keeping line numbers)
 *   <li>Converts them to records (required fields, types, ranges, ID format checks)
 *   <li>Checks references (whether a fish's region exists in regions.yml, etc.)
 *   <li>Runs extra checks ({@link ContentCheck})
 * </ol>
 *
 * Returns a new snapshot only when there are no errors at all. This class uses no server API, so
 * harbor-tools (CI) validates with the same code. It is not thread-safe, so registration happens on
 * one thread at startup.
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

    /** Reads all registered types. */
    public Result<ContentSnapshot, ValidationErrors> load(Path root) {
        return reload(root, types.keySet(), ContentSnapshot.empty());
    }

    /**
     * Re-reads only some types and keeps the previous snapshot's values for the rest. Reference
     * checks run on the whole merged result (references from types not re-read that point at
     * re-read types are rechecked using the references kept in the previous snapshot: e.g. a quest
     * was deleted but an NPC still points at it).
     *
     * @param only names of the types to re-read
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
        Map<String, List<ParseContext.Reference>> references = new HashMap<>();

        for (ContentType<?> type : types.values()) {
            if (!only.contains(type.name())) {
                copyPrevious(type, previous, entries, singles);
                references.put(
                        type.name(), previous.rawReferences().getOrDefault(type.name(), List.of()));
                continue;
            }
            ParseContext ctx = new ParseContext();
            switch (type) {
                case ContentType.Entries<?> e ->
                        parseEntries(root, files, e, ctx, errors, entries, positions);
                case ContentType.Single<?> s ->
                        parseSingle(root, files, s, ctx, errors, singles, singlePositions);
            }
            references.put(type.name(), ctx.references());
        }

        // Step 3: reference checks (only when the target type is registered). Checked against the
        // merged references of all types
        for (ContentType<?> type : types.values()) {
            for (ParseContext.Reference ref : references.getOrDefault(type.name(), List.of())) {
                if (!types.containsKey(ref.targetType())) {
                    continue;
                }
                Map<HarborId, Object> target = entries.get(ref.targetType());
                if (target == null || !target.containsKey(ref.id())) {
                    errors.add(ref.pos(), ref.targetType() + " '" + ref.id() + "'를 찾을 수 없습니다");
                }
            }
        }

        ContentSnapshot snapshot = new ContentSnapshot(entries, singles, references);

        // Step 4: extra checks (re-read types only)
        if (errors.isEmpty()) {
            runChecks(only, snapshot, positions, singlePositions, errors);
        }
        return errors.isEmpty() ? Result.ok(snapshot) : Result.err(errors);
    }

    // ------------------------------------------------------------------ internals

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
        return files.stream()
                .filter(f -> patterns.stream().anyMatch(p -> p.matcher(f).matches()))
                .toList();
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
                        SourcePos at =
                                pos.getOrDefault(entry.getKey(), new SourcePos(e.name(), 1, 1));
                        runChecks(typeChecks, entry.getValue(), snapshot, at, errors);
                    }
                }
                case ContentType.Single<?> s -> {
                    Object value = snapshot.rawSingles().get(s.name());
                    if (value != null) {
                        SourcePos at =
                                singlePositions.getOrDefault(
                                        s.name(), new SourcePos(s.file(), 1, 1));
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
