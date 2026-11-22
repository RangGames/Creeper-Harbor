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
 * Resource pack sound playback ('15. Sound'). Modules never play {@code harbor:} sounds directly;
 * they go through this (proposed).
 *
 * <p>A {@code harbor:<event>} sound is played only when both conditions hold; otherwise the vanilla
 * fallback sound from sounds.yml is played. If there is no fallback, nothing plays. A sound missing
 * from the pack is ignored by the client, so it would merely be silent, but the same conditions are
 * checked to pick the fallback.
 *
 * <ul>
 *   <li>balance.yml {@value #SETTING} is true (the resource pack is being served). If the key is
 *       absent, follows {@link ItemModels#SETTING}
 *   <li>the served resource pack ({@link ActivePack}: the auto-built pack, otherwise
 *       resource_pack.yml {@link ResourcePackIndex}) contains that sound
 * </ul>
 *
 * <p>Category (source), volume, pitch and loop interval come from the event settings in sounds.yml
 * ({@link SoundTable}). Other sound keys such as {@code minecraft:} are played as is (so a content
 * sound field using a vanilla sound goes through the same path). Main thread only.
 */
public final class HarborSounds {

    /** Whether to use resource pack sounds. If absent, follows items.custom_models. */
    public static final String SETTING = "sounds.custom";

    public static final String NAMESPACE = "harbor";

    /**
     * Default audible distance of positional sounds (blocks, at volume 1). The server sends only to
     * players within 16 × volume.
     */
    private static final double RANGE = 16;

    private static final int DEFAULT_LOOP_TICKS = 40;

    private HarborSounds() {}

    /**
     * Playback options.
     *
     * @param fallback fallback sound for this call only (null = the sounds.yml value)
     * @param source category for sounds not in the table (vanilla keys, etc.)
     * @param volume volume multiplier (multiplied with the table volume; for sounds not in the
     *     table, this is the volume)
     * @param pitch pitch multiplier (same rule)
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

    /**
     * Looping sound. Stop it with {@link #stop()} (sounds already played are also stopped with
     * stopSound).
     */
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

    // ------------------------------------------------------------------ checks

    public static boolean enabled(Balance balance) {
        return balance.string(SETTING)
                .map(Boolean::parseBoolean)
                .orElse(ItemModels.enabled(balance));
    }

    /** Whether this harbor sound can be played now (enabled, and listed in the resource pack). */
    public static boolean available(ContentSnapshot snapshot, String key) {
        if (!enabled(snapshot.single(Balance.TYPE))) {
            return false;
        }
        return ActivePack.hasSound(snapshot, key);
    }

    /**
     * Sound key → the sound actually played. Empty if the pack sound can't be used and there is no
     * fallback.
     */
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

    // ------------------------------------------------------------------ one-shot playback

    /**
     * To the player only, following the player (positionless sounds such as UI or catch rarity
     * cues).
     */
    public static void play(Player player, @Nullable String key) {
        play(player, key, Options.DEFAULT);
    }

    public static void play(Player player, @Nullable String key, Options options) {
        resolve(key, options).ifPresent(s -> player.playSound(s, Sound.Emitter.self()));
    }

    /** To several players, each in 2D. */
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

    /**
     * A sound from a position in the world. Heard by all nearby players (within 16 × volume
     * blocks).
     */
    public static void playAt(Location at, @Nullable String key) {
        playAt(at, key, Options.DEFAULT, List.of());
    }

    public static void playAt(Location at, @Nullable String key, Options options) {
        playAt(at, key, options, List.of());
    }

    /**
     * Positional sound, also sent directly to participants out of range (sounds that must be heard
     * from afar, like a boss roar; '15. Sound' 4.3).
     *
     * @param extra players who should hear it even out of range
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

    /** Positional sound for a single player (personal effects). */
    public static void playAt(Player player, Location at, @Nullable String key, Options options) {
        resolve(key, options).ifPresent(s -> player.playSound(s, at.getX(), at.getY(), at.getZ()));
    }

    /** Stops this sound (both the pack sound and the fallback). */
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

    // ------------------------------------------------------------------ looping

    /**
     * Looping sound heard only by the player (reeling, storm waters). sounds.json has no loop
     * option, so it is replayed every sounds.yml loop interval (sound length) ('15. Sound' chapter
     * 2). Ends on its own when the player leaves.
     *
     * @param tasks the module's scheduler helper that runs the loop (cancelled when the module is
     *     disabled)
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
     * Looping sound that follows an entity (boat sailing, airship). Each replay goes to nearby
     * players (within 16 × volume blocks); on stop it is cut for every player who heard it. Ends on
     * its own when the entity disappears.
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

    /** sounds.yml loop value (2 seconds if absent). */
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

    /** One loop iteration: the players it was played to, or null when finished. */
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

    // ------------------------------------------------------------------ internals

    private static SoundTable table(ContentSnapshot snapshot) {
        return snapshot.typeNames().contains(SoundTable.TYPE.name())
                ? snapshot.single(SoundTable.TYPE)
                : SoundTable.EMPTY;
    }

    private static Sound.Source source(String name) {
        Sound.Source source = Sound.Source.NAMES.value(name);
        return source == null ? Sound.Source.MASTER : source;
    }

    /** Paper marks location lookups as nullable, but a living entity always has a location. */
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
