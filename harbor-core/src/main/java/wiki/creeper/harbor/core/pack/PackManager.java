package wiki.creeper.harbor.core.pack;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.InfraConfig;
import wiki.creeper.harbor.common.pack.CdnClient;
import wiki.creeper.harbor.common.pack.PackBuilder;
import wiki.creeper.harbor.common.pack.PublishedPack;
import wiki.creeper.harbor.common.redis.MessageBus;
import wiki.creeper.harbor.common.redis.Redis;
import wiki.creeper.harbor.common.util.Json;
import wiki.creeper.harbor.core.api.ActivePack;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 리소스팩 자동 빌드·배포 (ItemsAdder 방식, 추가 제안).
 *
 * <ul>
 *   <li>모든 서버: 시작할 때 Redis {@value PublishedPack#KEY}에서 지금 팩을 읽어 {@link ActivePack}에 넣는다 (커스텀 모델·소리
 *       판정). {@link PublishedPack#CHANNEL} 알림을 받거나, 알림을 놓쳤을 때를 대비해 1분마다 SHA-1 키를 비교해서 바뀌었으면 다시 읽는다.
 *   <li>빌드 서버 (harbor-infra.yml pack.build: true, lobby 한 대): 시작할 때와 /harbor pack build 때 에셋 폴더를
 *       압축하고, SHA-1이 지금 팩과 다르면 CDN에 올린 뒤 Redis에 저장·발행한다.
 * </ul>
 *
 * 압축·업로드·Redis는 가상 스레드에서 한다.
 */
public final class PackManager {

    /** 빌드 결과 (명령 응답용). */
    public sealed interface Outcome {
        record Uploaded(PublishedPack pack, int files) implements Outcome {}

        record Unchanged(PublishedPack pack) implements Outcome {}

        record NoAssets(Path source) implements Outcome {}

        record Failed(String reason) implements Outcome {}
    }

    /** 알림을 놓쳤을 때를 대비한 확인 간격 (1분, 제안값). */
    private static final long POLL_TICKS = 20L * 60;

    private final InfraConfig infra;
    private final Path harborDir;
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
            Redis redis,
            MessageBus bus,
            Tasks tasks,
            Logger log) {
        this.infra = infra;
        this.harborDir = harborDir;
        this.redis = redis;
        this.bus = bus;
        this.tasks = tasks;
        this.log = log;
    }

    /** 지금 팩을 읽고 변경을 구독한다. 빌드 서버면 에셋 폴더를 준비하고 한 번 빌드한다. */
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
            } catch (IOException e) {
                log.warn("리소스팩 에셋 폴더를 만들지 못했습니다: {}", String.valueOf(e));
            }
            var _ = build().thenAccept(this::logOutcome);
        }
    }

    /**
     * Redis의 지금 팩이 내가 아는 것과 다르면 다시 읽어 적용한다 (가상 스레드에서 읽고 메인 스레드에서 적용).
     *
     * @param knownSha1 알림으로 받은 SHA-1 (null이면 Redis에서 확인)
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

    /** 이 서버가 팩을 만드는지. */
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

    /** 새 팩을 알게 됐을 때 (메인 스레드). lobby의 ResourcePacks가 구독한다. */
    public void onChange(Consumer<PublishedPack> listener) {
        listeners.add(listener);
    }

    /** 압축 → (바뀌었으면) 업로드 → 저장·발행. 결과는 메인 스레드에서 완료된다. */
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
            PackBuilder.Built built =
                    PackBuilder.build(
                            source,
                            out,
                            new PackBuilder.Meta(config.format(), config.description()));
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
