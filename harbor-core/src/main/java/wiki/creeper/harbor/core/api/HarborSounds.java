package wiki.creeper.harbor.core.api;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.ResourcePackIndex;
import wiki.creeper.harbor.common.content.type.SoundTable;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 리소스팩 소리 재생 ('15. 사운드'). 모든 모듈은 {@code harbor:} 소리를 직접 내지 않고 이것을 거친다 (추가 제안).
 *
 * <p>{@code harbor:<이벤트>} 소리는 두 조건을 모두 만족할 때만 내고, 아니면 sounds.yml의 바닐라 대체 소리(fallback)를 낸다. 대체 소리가
 * 없으면 조용히 넘어간다. 팩에 없는 소리를 보내면 클라이언트가 무시하므로 소리가 빠지기만 하지만, 대체 소리를 고르려고 같은 조건을 본다.
 *
 * <ul>
 *   <li>balance.yml {@value #SETTING}이 true (리소스팩을 배포 중). 키가 없으면 {@link ItemModels#SETTING}을 따른다
 *   <li>resource_pack.yml({@link ResourcePackIndex})에 그 소리가 있다. 목록이 비면 이 검사는 건너뛴다
 * </ul>
 *
 * <p>분류(source)·음량·피치·반복 간격은 sounds.yml({@link SoundTable})의 이벤트 설정을 쓴다. {@code minecraft:} 같은 다른
 * 소리 키는 그대로 낸다 (콘텐츠의 sound 필드가 바닐라 소리를 써도 같은 길로 재생). 메인 스레드 전용.
 */
public final class HarborSounds {

    /** 리소스팩 소리를 쓸지. 없으면 items.custom_models를 따른다. */
    public static final String SETTING = "sounds.custom";

    public static final String NAMESPACE = "harbor";

    /** 위치 소리가 들리는 기본 거리 (블록, 음량 1 기준). 서버는 16 × volume 안의 플레이어에게만 보낸다. */
    private static final double RANGE = 16;

    private static final int DEFAULT_LOOP_TICKS = 40;

    private HarborSounds() {}

    /**
     * 재생 옵션.
     *
     * @param fallback 이번 호출만 쓸 대체 소리 (null이면 sounds.yml 값)
     * @param source 표에 없는 소리(바닐라 키 등)의 분류
     * @param volume 음량 배율 (표 음량에 곱한다. 표에 없는 소리는 이 값이 음량)
     * @param pitch 피치 배율 (같은 방식)
     */
    public record Options(
            @Nullable String fallback, Sound.Source source, float volume, float pitch) {

        public static final Options DEFAULT = new Options(null, Sound.Source.MASTER, 1f, 1f);

        public static Options fallback(@Nullable String fallback) {
            return DEFAULT.withFallback(fallback);
        }

        public Options withFallback(@Nullable String value) {
            return new Options(
                    value == null || value.isBlank() ? null : value, source, volume, pitch);
        }

        public Options withSource(Sound.Source value) {
            return new Options(fallback, value, volume, pitch);
        }

        public Options withVolume(float value) {
            return new Options(fallback, source, value, pitch);
        }

        public Options withPitch(float value) {
            return new Options(fallback, source, volume, value);
        }
    }

    /** 반복 소리. {@link #stop()}으로 끊는다 (이미 낸 소리도 stopSound로 멈춘다). */
    public interface Loop {

        Loop NONE =
                new Loop() {
                    @Override
                    public void stop() {}

                    @Override
                    public boolean active() {
                        return false;
                    }
                };

        void stop();

        boolean active();
    }

    // ------------------------------------------------------------------ 판정

    public static boolean enabled(Balance balance) {
        return balance.string(SETTING)
                .map(Boolean::parseBoolean)
                .orElse(ItemModels.enabled(balance));
    }

    /** 이 harbor 소리를 지금 낼 수 있는지 (켜져 있고, 리소스팩 목록에 있다). */
    public static boolean available(ContentSnapshot snapshot, String key) {
        if (!enabled(snapshot.single(Balance.TYPE))) {
            return false;
        }
        return !snapshot.typeNames().contains(ResourcePackIndex.TYPE.name())
                || snapshot.single(ResourcePackIndex.TYPE).hasSound(key);
    }

    /** 소리 키 → 실제로 낼 소리. 팩 소리를 못 쓰고 대체 소리도 없으면 빈 값. */
    public static Optional<Sound> resolve(@Nullable String key, Options options) {
        return resolve(Harbor.api().content().snapshot(), key, options);
    }

    public static Optional<Sound> resolve(
            ContentSnapshot snapshot, @Nullable String key, Options options) {
        Key parsed = parse(key);
        if (parsed == null) {
            return Optional.empty();
        }
        if (!parsed.namespace().equals(NAMESPACE)) {
            return Optional.of(
                    Sound.sound(parsed, options.source(), options.volume(), options.pitch()));
        }
        SoundTable.Event event = table(snapshot).event(parsed.value()).orElse(null);
        Sound.Source source = event == null ? options.source() : source(event.source());
        if (available(snapshot, parsed.asString())) {
            float volume = event == null ? 1f : event.volume();
            float pitch = event == null ? 1f : event.pitch();
            return Optional.of(
                    Sound.sound(
                            parsed, source, volume * options.volume(), pitch * options.pitch()));
        }
        String fallback = options.fallback();
        if (fallback == null && event != null) {
            fallback = event.fallback();
        }
        Key fallbackKey = parse(fallback);
        if (fallbackKey == null) {
            return Optional.empty();
        }
        float volume = event == null ? 1f : event.fallbackVolume();
        float pitch = event == null ? 1f : event.fallbackPitch();
        return Optional.of(
                Sound.sound(
                        fallbackKey, source, volume * options.volume(), pitch * options.pitch()));
    }

    // ------------------------------------------------------------------ 한 번 재생

    /** 본인에게만, 본인을 따라가는 소리 (UI·포획 등급음처럼 위치가 없는 소리). */
    public static void play(Player player, @Nullable String key) {
        play(player, key, Options.DEFAULT);
    }

    public static void play(Player player, @Nullable String key, Options options) {
        resolve(key, options).ifPresent(s -> player.playSound(s, Sound.Emitter.self()));
    }

    /** 여러 명에게 각자 2D로. */
    public static void play(
            Collection<? extends Player> players, @Nullable String key, Options options) {
        if (players.isEmpty()) {
            return;
        }
        resolve(key, options)
                .ifPresent(
                        s -> {
                            for (Player p : players) {
                                p.playSound(s, Sound.Emitter.self());
                            }
                        });
    }

    /** 월드의 한 위치에서 나는 소리. 근처(16 × 음량 블록) 플레이어 모두에게 들린다. */
    public static void playAt(Location at, @Nullable String key) {
        playAt(at, key, Options.DEFAULT, List.of());
    }

    public static void playAt(Location at, @Nullable String key, Options options) {
        playAt(at, key, options, List.of());
    }

    /**
     * 위치 소리 + 범위 밖에 있는 참가자에게도 직접 보낸다 (보스 포효처럼 멀리서도 들려야 하는 소리, '15. 사운드' 4.3).
     *
     * @param extra 범위 밖이어도 들려줄 플레이어
     */
    public static void playAt(
            Location at,
            @Nullable String key,
            Options options,
            Collection<? extends Player> extra) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        resolve(key, options)
                .ifPresent(
                        s -> {
                            world.playSound(s, at.getX(), at.getY(), at.getZ());
                            double range = RANGE * Math.max(1f, s.volume());
                            for (Player p : extra) {
                                if (!p.getWorld().equals(world)
                                        || loc(p).distanceSquared(at) > range * range) {
                                    p.playSound(s, Sound.Emitter.self());
                                }
                            }
                        });
    }

    /** 위치 소리를 한 명에게만 (개인 연출). */
    public static void playAt(Player player, Location at, @Nullable String key, Options options) {
        resolve(key, options).ifPresent(s -> player.playSound(s, at.getX(), at.getY(), at.getZ()));
    }

    /** 이 소리(팩 소리와 대체 소리 모두)를 멈춘다. */
    public static void stop(Player player, @Nullable String key) {
        stop(player, key, Options.DEFAULT);
    }

    public static void stop(Player player, @Nullable String key, Options options) {
        Key parsed = parse(key);
        if (parsed == null) {
            return;
        }
        ContentSnapshot snapshot = Harbor.api().content().snapshot();
        SoundTable.Event event =
                parsed.namespace().equals(NAMESPACE)
                        ? table(snapshot).event(parsed.value()).orElse(null)
                        : null;
        Sound.Source source = event == null ? options.source() : source(event.source());
        player.stopSound(SoundStop.namedOnSource(parsed, source));
        String fallback = options.fallback();
        if (fallback == null && event != null) {
            fallback = event.fallback();
        }
        Key fallbackKey = parse(fallback);
        if (fallbackKey != null) {
            player.stopSound(SoundStop.namedOnSource(fallbackKey, source));
        }
    }

    // ------------------------------------------------------------------ 반복

    /**
     * 본인에게만 들리는 반복 소리 (릴 감기, 폭풍 해역). sounds.json에는 반복 옵션이 없어서 sounds.yml loop 간격(소리 길이)마다 다시 낸다
     * ('15. 사운드' 2장). 플레이어가 나가면 저절로 끝난다.
     *
     * @param tasks 반복 작업을 맡을 모듈의 예약 도우미 (모듈이 꺼지면 같이 취소)
     */
    public static Loop loop(Tasks tasks, Player player, @Nullable String key, Options options) {
        Optional<Sound> sound = resolve(key, options);
        if (sound.isEmpty()) {
            return Loop.NONE;
        }
        Sound s = sound.get();
        UUID uuid = player.getUniqueId();
        return new TaskLoop(
                tasks,
                loopTicks(key),
                s,
                () -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null) {
                        return null;
                    }
                    p.playSound(s, Sound.Emitter.self());
                    return Set.of(p);
                });
    }

    /**
     * 엔티티를 따라가는 반복 소리 (배 항해, 비행선). 다시 낼 때마다 근처(16 × 음량 블록) 플레이어에게 보내고, 멈출 때 들은 플레이어 모두에게서 끊는다. 엔티티가
     * 사라지면 저절로 끝난다.
     */
    public static Loop loopFrom(
            Tasks tasks, Entity emitter, @Nullable String key, Options options) {
        Optional<Sound> sound = resolve(key, options);
        if (sound.isEmpty()) {
            return Loop.NONE;
        }
        Sound s = sound.get();
        double range = RANGE * Math.max(1f, s.volume());
        return new TaskLoop(
                tasks,
                loopTicks(key),
                s,
                () -> {
                    if (!emitter.isValid()) {
                        return null;
                    }
                    Location at = loc(emitter);
                    Set<Player> heard = new HashSet<>();
                    for (Player p : emitter.getWorld().getPlayers()) {
                        if (loc(p).distanceSquared(at) <= range * range) {
                            p.playSound(s, emitter);
                            heard.add(p);
                        }
                    }
                    return heard;
                });
    }

    /** sounds.yml loop 값 (없으면 2초). */
    public static int loopTicks(@Nullable String key) {
        Key parsed = parse(key);
        if (parsed == null || !parsed.namespace().equals(NAMESPACE)) {
            return DEFAULT_LOOP_TICKS;
        }
        return table(Harbor.api().content().snapshot())
                .event(parsed.value())
                .map(SoundTable.Event::loopTicks)
                .filter(t -> t > 0)
                .orElse(DEFAULT_LOOP_TICKS);
    }

    /** 반복 한 번: 낸 플레이어 목록, null이면 끝. */
    private interface Emit {
        @Nullable Set<Player> emit();
    }

    private static final class TaskLoop implements Loop {

        private final Sound sound;
        private final Emit emit;
        private final Set<UUID> heard = new HashSet<>();
        private final BukkitTask task;
        private boolean active = true;

        TaskLoop(Tasks tasks, int period, Sound sound, Emit emit) {
            this.sound = sound;
            this.emit = emit;
            this.task = tasks.timer(0, Math.max(1, period), this::tick);
        }

        private void tick() {
            if (!active) {
                return;
            }
            Set<Player> players = emit.emit();
            if (players == null) {
                stop();
                return;
            }
            players.forEach(p -> heard.add(p.getUniqueId()));
        }

        @Override
        public void stop() {
            if (!active) {
                return;
            }
            active = false;
            task.cancel();
            SoundStop stop = SoundStop.namedOnSource(sound.name(), sound.source());
            for (UUID uuid : heard) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    p.stopSound(stop);
                }
            }
            heard.clear();
        }

        @Override
        public boolean active() {
            return active;
        }
    }

    // ------------------------------------------------------------------ 내부

    private static SoundTable table(ContentSnapshot snapshot) {
        return snapshot.typeNames().contains(SoundTable.TYPE.name())
                ? snapshot.single(SoundTable.TYPE)
                : SoundTable.EMPTY;
    }

    private static Sound.Source source(String name) {
        Sound.Source source = Sound.Source.NAMES.value(name);
        return source == null ? Sound.Source.MASTER : source;
    }

    /** Paper의 위치 조회는 null 가능으로 표시되어 있지만 살아 있는 엔티티는 항상 위치가 있다. */
    private static Location loc(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }

    private static @Nullable Key parse(@Nullable String key) {
        if (key == null || key.isBlank() || !Key.parseable(key)) {
            return null;
        }
        return Key.key(key);
    }
}
