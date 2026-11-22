package wiki.creeper.harbor.core.pack;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;
import wiki.creeper.harbor.common.pack.CdnClient;
import wiki.creeper.harbor.common.pack.FurnishingPack;
import wiki.creeper.harbor.common.pack.PackBuilder;
import wiki.creeper.harbor.common.pack.PublishedPack;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.util.Json;
import wiki.creeper.harbor.core.api.ActivePack;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Automatic resource pack build and distribution (ItemsAdder style, proposed).
 *
 * <ul>
 *   <li>All servers: at startup, read the current pack from Redis {@value PublishedPack#KEY} into
 *       {@link ActivePack} (custom model and sound checks). Re-read when a {@link
 *       PublishedPack#CHANNEL} notification arrives, or when the SHA-1 key differs on the 1-minute
 *       check (in case a notification was missed).
 *   <li>Build server (harbor-infra.yml pack.build: true, one lobby): at startup and on /harbor pack
 *       build, zip the asset folder and, if the SHA-1 differs from the current pack, upload to the
 *       CDN and then store and publish in Redis.
 * </ul>
 *
 * Zipping, uploading and Redis run on virtual threads.
 */
public final class PackManager {

    /** Build result (for command replies). */
    public sealed interface Outcome {
        record Uploaded(PublishedPack pack, int files) implements Outcome {}

        record Unchanged(PublishedPack pack) implements Outcome {}

        record NoAssets(Path source) implements Outcome {}

        record Failed(String reason) implements Outcome {}
    }

    /** Check interval in case a notification was missed (1 minute, proposed value). */
    private static final long POLL_TICKS = 20L * 60;

    private final InfraConfig infra;
    private final Path harborDir;
    private final Supplier<ContentSnapshot> content;
    private final Redis redis;
    private final MessageBus bus;
    private final Tasks tasks;
    private final Logger log;
    private final AtomicBoolean building = new AtomicBoolean();
    private final CopyOnWriteArrayList<Consumer<PublishedPack>> listeners =
            new CopyOnWriteArrayList<>();

    public PackManager(
            InfraConfig infra,
            Path harborDir,
            Supplier<ContentSnapshot> content,
            Redis redis,
            MessageBus bus,
            Tasks tasks,
            Logger log) {
        this.infra = infra;
        this.harborDir = harborDir;
        this.content = content;
        this.redis = redis;
        this.bus = bus;
        this.tasks = tasks;
        this.log = log;
    }

    /**
     * Reads the current pack and subscribes to changes. On the build server, prepares the asset
     * folder and builds once.
     */
    public void start() {
        bus.subscribe(PublishedPack.CHANNEL, envelope -> refresh(envelope.payload().sha1()));
        refresh(null);
        var _ = tasks.timer(POLL_TICKS, POLL_TICKS, () -> refresh(null));
        InfraConfig.PackBuild config = infra.packBuild();
        if (config != null && config.apiKey().isBlank()) {
            log.warn("harbor-infra.yml pack.build가 켜져 있지만 cdn.api_key가 비어 있어 리소스팩을 만들지 않습니다");
        }
        if (builder()) {
            try {
                Path source = source();
                Files.createDirectories(source.resolve("assets"));
                Path readme = source.resolve("README.txt");
                if (Files.notExists(readme)) {
                    Files.writeString(readme, README, StandardCharsets.UTF_8);
                }
                Files.createDirectories(mergeDir());
                Path mergeReadme = mergeDir().resolve("README.txt");
                if (Files.notExists(mergeReadme)) {
                    Files.writeString(mergeReadme, MERGE_README, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                log.warn("리소스팩 에셋 폴더를 만들지 못했습니다: {}", String.valueOf(e));
            }
            var _ = build().thenAccept(this::logOutcome);
        }
    }

    /**
     * If the current pack in Redis differs from what this server knows, re-reads and applies it
     * (read on a virtual thread, applied on the main thread).
     *
     * @param knownSha1 SHA-1 received in the notification (null to check Redis)
     */
    private void refresh(@Nullable String knownSha1) {
        var _ =
                tasks.supplyAsync(
                                () -> {
                                    String latest =
                                            knownSha1 != null
                                                    ? knownSha1
                                                    : redis.sync().get(PublishedPack.SHA1_KEY);
                                    Optional<PublishedPack> current = ActivePack.published();
                                    if (current.isPresent()
                                            && (latest == null
                                                    || current.get().sha1().equals(latest))) {
                                        return Optional.<PublishedPack>empty();
                                    }
                                    return readStored();
                                })
                        .thenAcceptAsync(stored -> stored.ifPresent(this::accept), tasks.main())
                        .exceptionally(
                                e -> {
                                    log.warn("리소스팩 정보를 읽지 못했습니다: {}", String.valueOf(e));
                                    return null;
                                });
    }

    /** Whether this server builds the pack. */
    public boolean builder() {
        InfraConfig.PackBuild b = infra.packBuild();
        return b != null && !b.apiKey().isBlank();
    }

    public Path source() {
        InfraConfig.PackBuild b = infra.packBuild();
        Path configured = b == null ? null : b.source();
        return configured != null ? configured : harborDir.resolve("pack");
    }

    public Optional<PublishedPack> current() {
        return ActivePack.published();
    }

    /** When a new pack becomes known (main thread). Subscribed by the lobby's ResourcePacks. */
    public void onChange(Consumer<PublishedPack> listener) {
        listeners.add(listener);
    }

    /** Zip → upload (if changed) → store and publish. The result completes on the main thread. */
    public CompletableFuture<Outcome> build() {
        InfraConfig.PackBuild config = infra.packBuild();
        if (config == null || config.apiKey().isBlank()) {
            return CompletableFuture.completedFuture(
                    new Outcome.Failed(
                            "이 서버는 리소스팩 빌드 서버가 아닙니다 (harbor-infra.yml pack.build·cdn.api_key)"));
        }
        if (!building.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new Outcome.Failed("이미 빌드 중입니다"));
        }
        Path source = source();
        Path out = harborDir.resolve("pack-build").resolve(config.fileName());
        return tasks.supplyAsync(() -> buildBlocking(config, source, out))
                .thenApplyAsync(
                        outcome -> {
                            building.set(false);
                            if (outcome
                                    instanceof Outcome.Uploaded(PublishedPack pack, int files)) {
                                accept(pack);
                            }
                            return outcome;
                        },
                        tasks.main());
    }

    private Outcome buildBlocking(InfraConfig.PackBuild config, Path source, Path out) {
        try {
            if (!PackBuilder.hasAssets(source) || isEmpty(source.resolve("assets"))) {
                return new Outcome.NoAssets(source);
            }
            FurnishingPack.Result furnishing =
                    FurnishingPack.generate(
                            content.get().all(CustomBlockDef.TYPE).values(),
                            path -> Files.isRegularFile(source.resolve(path)));
            furnishing.warnings().forEach(w -> log.warn("커스텀 블록: {}", w));
            if (!furnishing.files().isEmpty()
                    && Files.isRegularFile(source.resolve(FurnishingPack.BLOCKSTATE))) {
                log.warn("에셋 폴더의 {}는 커스텀 블록용 생성 파일로 바뀝니다", FurnishingPack.BLOCKSTATE);
            }
            List<Path> merges = merges(config);
            PackBuilder.Built built =
                    PackBuilder.build(
                            source,
                            merges,
                            out,
                            new PackBuilder.Meta(config.format(), config.description()),
                            furnishing.files());
            if (!merges.isEmpty()) {
                log.info(
                        "리소스팩 합치기: {}",
                        merges.stream().map(p -> String.valueOf(p.getFileName())).toList());
            }
            built.notes().forEach(n -> log.warn("리소스팩 합치기: {}", n));
            Optional<PublishedPack> stored = readStored();
            if (stored.isPresent() && stored.get().sha1().equals(built.sha1())) {
                redis.sync().set(PublishedPack.SHA1_KEY, built.sha1());
                return new Outcome.Unchanged(stored.get());
            }
            CdnClient.Uploaded uploaded =
                    new CdnClient(URI.create(config.cdnUrl()), config.apiKey())
                            .upload(built.zip(), config.namespace(), config.version());
            PublishedPack pack =
                    new PublishedPack(
                            uploaded.url(),
                            built.sha1(),
                            built.sizeBytes(),
                            new ArrayList<>(built.index().models()),
                            new ArrayList<>(built.index().sounds()),
                            System.currentTimeMillis());
            redis.sync().set(PublishedPack.KEY, Json.GSON.toJson(pack));
            redis.sync().set(PublishedPack.SHA1_KEY, pack.sha1());
            bus.publish(PublishedPack.CHANNEL, new PublishedPack.Changed(pack.sha1()));
            return new Outcome.Uploaded(pack, built.files());
        } catch (IOException | RuntimeException e) {
            return new Outcome.Failed(String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome.Failed("중단됨");
        }
    }

    /**
     * Packs to merge: harbor-infra.yml pack.merge (in listed order) + folders and zips in
     * plugins/Harbor/pack-merge/ (by name).
     */
    private List<Path> merges(InfraConfig.PackBuild config) throws IOException {
        List<Path> list = new ArrayList<>(config.merge());
        Path dir = mergeDir();
        if (Files.isDirectory(dir)) {
            try (var children = Files.list(dir)) {
                children.filter(
                                p ->
                                        Files.isDirectory(p)
                                                || String.valueOf(p.getFileName())
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                        .endsWith(".zip"))
                        .sorted()
                        .forEach(list::add);
            }
        }
        return list;
    }

    private Path mergeDir() {
        return harborDir.resolve("pack-merge");
    }

    private Optional<PublishedPack> readStored() {
        @Nullable String raw = redis.sync().get(PublishedPack.KEY);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(Json.GSON.fromJson(raw, PublishedPack.class));
    }

    private void accept(PublishedPack pack) {
        Optional<PublishedPack> before = ActivePack.published();
        if (before.isPresent() && before.get().sha1().equals(pack.sha1())) {
            return;
        }
        ActivePack.publish(pack);
        log.info(
                "리소스팩 적용: {} (모델 {}개, 소리 {}개)",
                pack.url(),
                pack.models().size(),
                pack.sounds().size());
        listeners.forEach(l -> l.accept(pack));
    }

    private void logOutcome(Outcome outcome) {
        switch (outcome) {
            case Outcome.Uploaded(PublishedPack pack, int files) ->
                    log.info("리소스팩을 새로 올렸습니다: 파일 {}개, SHA-1 {}", files, pack.sha1());
            case Outcome.Unchanged(PublishedPack pack) ->
                    log.info("리소스팩이 바뀌지 않았습니다 (SHA-1 {})", pack.sha1());
            case Outcome.NoAssets(Path source) ->
                    log.warn("리소스팩 에셋이 없습니다. {}/assets 에 에셋을 넣으세요", source);
            case Outcome.Failed(String reason) -> log.error("리소스팩 빌드 실패: {}", reason);
        }
    }

    private static boolean isEmpty(Path dir) throws IOException {
        try (var children = Files.list(dir)) {
            return children.findAny().isEmpty();
        }
    }

    private static final String MERGE_README =
            """
            합칠 리소스팩 폴더 (자동 빌드, 추가 제안)

            다른 플러그인·외부에서 받은 리소스팩(.zip 또는 압축을 푼 폴더)을 여기에 넣으면 /harbor pack build 때 Harbor 팩과 합친다.
            다른 플러그인 폴더에 있는 팩을 그대로 쓰려면 harbor-infra.yml pack.merge에 경로를 적는다.

            우선순위: Harbor 팩(plugins/Harbor/pack) > pack.merge에 적은 순서 > 이 폴더(이름순)
            sounds.json · lang 은 키 단위로, atlases 는 sources 합집합으로, font 는 providers 를 이어 붙여 합친다.
            그 밖에 같은 경로의 파일은 우선순위가 높은 팩 것을 쓰고 서버 로그에 겹친 파일을 남긴다.
            pack.mcmeta · pack.png 는 Harbor 팩 것만 쓴다. zip 안에 폴더가 한 겹 더 있어도 assets를 찾아 쓴다.
            """;

    private static final String README =
            """
            크리퍼 항구 리소스팩 에셋 폴더 (자동 빌드)

            이 폴더에 리소스팩 내용을 그대로 넣는다:
              assets/<네임스페이스>/items, models, textures, sounds, sounds.json, lang ...
              pack.mcmeta, pack.png (선택. pack.mcmeta가 없으면 harbor-infra.yml pack.format으로 만든다)

            서버가 시작할 때와 /harbor pack build 때 압축해서, 바뀌었으면 CDN에 올리고 모든 서버에 알린다.
            assets/<네임스페이스>/rigs (서버용 리그 데이터)와 숨김 파일은 팩에 넣지 않는다.
            """;
}
