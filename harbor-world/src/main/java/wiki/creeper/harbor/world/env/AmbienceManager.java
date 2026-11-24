package wiki.creeper.harbor.world.env;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.sound.SoundStop;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.SoundTable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.content.AmbienceDef;
import wiki.creeper.harbor.world.region.RegionManager;

/**
 * Plays region ambience without a datapack (world/ambience.yml, "4. Fishing presentation" 1.3).
 * Every 10 ticks, for each player in a region with an ambience entry: background music and ambient
 * loops (vanilla music is cut each time the region music restarts), the region post effect and the
 * post effects of time-of-day phases at least half faded in (26.3 Player#postEffects; other post
 * effects on the player are kept), personal weather, and ambient particles scaled by phase strength
 * (sent to that player only). Overworld time drives the phases on every world, so Nether/End
 * regions follow overworld day and night. Presentation intensity 0 (/낚시 설정) skips post effects and
 * halves particles. Main thread only.
 */
public final class AmbienceManager implements Listener {

    private static final int PERIOD = 10;
    private static final String INTENSITY_FLAG = "fishing/settings/intensity";
    private static final Sound.Source MUSIC = Sound.Source.MUSIC;

    /** What is currently applied to one player. */
    private static final class State {
        @Nullable RegionId region;
        @Nullable String music;
        @Nullable String ambient;
        HarborSounds.Loop ambientLoop = HarborSounds.Loop.NONE;
        int musicTicks;
        final Set<Key> effects = new HashSet<>();
        boolean weather;
    }

    private final WorldKit kit;
    private final RegionManager regions;
    private final Map<UUID, State> states = new HashMap<>();
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final Set<String> unknownParticles = new HashSet<>();

    public AmbienceManager(WorldKit kit, RegionManager regions) {
        this.kit = kit;
        this.regions = regions;
    }

    public void start() {
        var _ = kit.tasks().timer(PERIOD, PERIOD, this::tick);
    }

    public void stop() {
        for (Map.Entry<UUID, State> e : states.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null) {
                clear(player, e.getValue());
            }
        }
        states.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        State state = states.remove(event.getPlayer().getUniqueId());
        if (state != null) {
            state.ambientLoop.stop();
        }
    }

    private void tick() {
        long time = overworldTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<AmbienceDef> def =
                    regions.current(player.getUniqueId())
                            .flatMap(
                                    r ->
                                            kit.api()
                                                    .content()
                                                    .snapshot()
                                                    .get(AmbienceDef.TYPE, r.id()));
            State state = states.get(player.getUniqueId());
            if (def.isEmpty()) {
                if (state != null) {
                    clear(player, state);
                    states.remove(player.getUniqueId());
                }
                continue;
            }
            if (state == null) {
                state = new State();
                states.put(player.getUniqueId(), state);
            }
            apply(player, state, def.get(), time);
        }
    }

    private void apply(Player player, State state, AmbienceDef def, long time) {
        boolean entered = !def.id().equals(state.region);
        state.region = def.id();
        int intensity = intensity(player);
        sounds(player, state, def, entered);
        if (def.weather() != null) {
            player.setPlayerWeather(
                    "downfall".equals(def.weather()) ? WeatherType.DOWNFALL : WeatherType.CLEAR);
            state.weather = true;
        } else if (state.weather) {
            player.resetPlayerWeather();
            state.weather = false;
        }
        Set<Key> effects = new HashSet<>();
        double scale = intensity == 0 ? 0.5 : 1;
        particles(player, def.particles(), scale);
        String base = def.postEffect();
        if (base != null) {
            effects.add(Key.key(base));
        }
        for (AmbienceDef.Phase phase : def.phases()) {
            double strength = phase.strength(time);
            if (strength <= 0) {
                continue;
            }
            particles(player, phase.particles(), strength * scale);
            String effect = phase.postEffect();
            if (effect != null && strength >= 0.5) {
                effects.add(Key.key(effect));
            }
        }
        if (intensity == 0) {
            effects.clear();
        }
        postEffects(player, state, effects);
    }

    /** Region music (cuts vanilla music on each restart) and the ambient loop. */
    private void sounds(Player player, State state, AmbienceDef def, boolean entered) {
        String music = def.music();
        if (!Objects.equals(music, state.music)) {
            if (state.music != null) {
                player.stopSound(SoundStop.source(MUSIC));
            }
            state.music = music;
            state.musicTicks = 0;
        }
        if (music != null) {
            state.musicTicks -= PERIOD;
            if (state.musicTicks <= 0) {
                player.stopSound(SoundStop.source(MUSIC));
                HarborSounds.play(player, music, HarborSounds.Options.DEFAULT.withSource(MUSIC));
                state.musicTicks = loopTicks(music);
            }
        }
        String ambient = def.ambient();
        if (entered || !Objects.equals(ambient, state.ambient)) {
            state.ambientLoop.stop();
            state.ambient = ambient;
            state.ambientLoop =
                    HarborSounds.loop(
                            kit.tasks(),
                            player,
                            ambient,
                            HarborSounds.Options.DEFAULT.withSource(Sound.Source.AMBIENT));
        }
    }

    /** sounds.yml loop of the music (5 minutes if absent, proposed). */
    private int loopTicks(String key) {
        String name = key.startsWith("harbor:") ? key.substring("harbor:".length()) : key;
        int loop =
                kit.api()
                        .content()
                        .snapshot()
                        .single(SoundTable.TYPE)
                        .event(name)
                        .map(SoundTable.Event::loopTicks)
                        .orElse(0);
        return loop > 0 ? loop : 6000;
    }

    private void postEffects(Player player, State state, Set<Key> wanted) {
        boolean changed = false;
        for (Key key : List.copyOf(state.effects)) {
            if (!wanted.contains(key)) {
                changed |= player.postEffects().remove(key);
                state.effects.remove(key);
            }
        }
        for (Key key : wanted) {
            if (state.effects.add(key)) {
                changed |= player.postEffects().add(key);
            }
        }
        if (changed) {
            player.postEffects().update();
        }
    }

    private void particles(Player player, List<AmbienceDef.ParticleSpec> specs, double strength) {
        if (specs.isEmpty() || strength <= 0) {
            return;
        }
        Location feet = WorldKit.loc(player);
        for (AmbienceDef.ParticleSpec spec : specs) {
            Particle particle = particle(spec.type());
            if (particle == null) {
                continue;
            }
            double expected = spec.count() * strength;
            int count = (int) expected + (random.nextDouble() < expected % 1 ? 1 : 0);
            String color = spec.color();
            Particle.DustOptions dust =
                    color == null
                            ? null
                            : new Particle.DustOptions(
                                    Color.fromRGB(Integer.parseInt(color.substring(1), 16)), 1.2f);
            for (int i = 0; i < count; i++) {
                Location at = point(feet, spec);
                if (at == null) {
                    continue;
                }
                try {
                    if (dust != null) {
                        player.spawnParticle(particle, at, 1, 0, 0, 0, 0, dust);
                    } else {
                        player.spawnParticle(particle, at, 1, 0.1, 0.05, 0.1, 0.01);
                    }
                } catch (IllegalArgumentException e) {
                    // Particles needing other data are skipped
                }
            }
        }
    }

    /** A random point around the player (on a water surface for water particles). */
    private @Nullable Location point(Location feet, AmbienceDef.ParticleSpec spec) {
        double angle = random.nextDouble() * Math.PI * 2;
        double r = Math.sqrt(random.nextDouble()) * spec.radius();
        double x = feet.getX() + Math.cos(angle) * r;
        double z = feet.getZ() + Math.sin(angle) * r;
        if (!spec.water()) {
            double y =
                    feet.getY() + spec.minY() + random.nextDouble() * (spec.maxY() - spec.minY());
            return new Location(feet.getWorld(), x, y, z);
        }
        World world = feet.getWorld();
        if (world == null) {
            return null;
        }
        int top = feet.getBlockY() + (int) Math.ceil(spec.maxY()) + 2;
        int bottom = feet.getBlockY() + (int) Math.floor(spec.minY()) - 6;
        for (int y = top; y >= bottom; y--) {
            Block block = world.getBlockAt((int) Math.floor(x), y, (int) Math.floor(z));
            if (block.getType() == Material.WATER) {
                if (block.getRelative(0, 1, 0).getType().isAir()) {
                    return new Location(world, x, y + 1.05, z);
                }
                return null;
            }
        }
        return null;
    }

    private @Nullable Particle particle(String name) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            if (unknownParticles.add(name)) {
                kit.log().warn("world/ambience.yml: 알 수 없는 파티클 '{}'", name);
            }
            return null;
        }
    }

    private void clear(Player player, State state) {
        state.ambientLoop.stop();
        state.ambientLoop = HarborSounds.Loop.NONE;
        if (state.music != null) {
            player.stopSound(SoundStop.source(MUSIC));
        }
        if (!state.effects.isEmpty()) {
            boolean changed = false;
            for (Key key : state.effects) {
                changed |= player.postEffects().remove(key);
            }
            state.effects.clear();
            if (changed) {
                player.postEffects().update();
            }
        }
        if (state.weather) {
            player.resetPlayerWeather();
        }
    }

    private int intensity(Player player) {
        return kit.api()
                .flags()
                .playerValue(player.getUniqueId(), INTENSITY_FLAG)
                .map(
                        v -> {
                            try {
                                return Math.clamp(Integer.parseInt(v), 0, 2);
                            } catch (NumberFormatException e) {
                                return 2;
                            }
                        })
                .orElse(2);
    }

    /** Overworld time (the first world); Nether/End regions follow it. */
    private static long overworldTime() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? 6000 : worlds.getFirst().getTime();
    }
}
