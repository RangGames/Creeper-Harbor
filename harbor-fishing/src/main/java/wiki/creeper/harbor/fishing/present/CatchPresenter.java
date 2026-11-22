package wiki.creeper.harbor.fishing.present;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.rig.FishRigs;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.core.api.RigService;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.Locs;
import wiki.creeper.harbor.fishing.api.CatchPresentationOverride;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * Plays grade presentation presets (fishing/presets.yml, "4. Fishing presentation & boss fights"
 * chapter 1). Shows the sound, particles, darkness, title, light pillar, nearby notice and result
 * Dialog of the bite and catch steps according to the presentation intensity setting. For the catch
 * presentation, a Shop cosmetic (CatchPresentationOverride), if any, gets it first. Main thread
 * only.
 */
public final class CatchPresenter {

    private static final double NEARBY_RADIUS = 32;
    private static final int PILLAR_TICKS = 40;
    private static final String FLOP = "flop";
    private static final int FLOP_TICKS = 30;
    private static final double FLOP_VIEW_RADIUS = 16;

    /** Vanilla sounds not in the table (sounds.yml) play in the player category. */
    public static final HarborSounds.Options SOUND =
            HarborSounds.Options.DEFAULT.withSource(Sound.Source.PLAYER);

    private final HarborApi api;
    private final Tasks tasks;

    public CatchPresenter(HarborApi api, Tasks tasks) {
        this.api = api;
        this.tasks = tasks;
    }

    private FishingPresets presets() {
        return api.content().snapshot().single(FishingPresets.TYPE);
    }

    /** Bite presentation (bobber location). */
    public void bite(Player player, FishDef fish, Location hook, int intensity) {
        FishingPresets presets = presets();
        FishingPresets.Stage stage = presets.of(fish.grade()).bite();
        // The bite plays at the bobber (nearby players hear it too; legendary bites carry far
        // because of their sounds.yml volume)
        HarborSounds.playAt(hook, stage.sound(), options(stage));
        particle(hook, stage.particle(), stage.particleCount());
        if (stage.has("darkness") && presets.shows(intensity, "darkness")) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, true, false));
        }
        if (stage.has("title") && presets.shows(intensity, "title")) {
            api.ui()
                    .title(
                            player,
                            api.messages().get("fishing.present.big_bite"),
                            Component.empty(),
                            Duration.ofMillis(100),
                            Duration.ofMillis(1500),
                            Duration.ofMillis(300));
        }
        if (stage.has("shake") && presets.shows(intensity, "shake")) {
            // There is no client screen shake API, so a short rumble sound is used instead of the
            // hurt shake
            player.playSound(
                    Sound.sound(
                            Key.key("minecraft:entity.warden.heartbeat"),
                            Sound.Source.PLAYER,
                            1f,
                            1.4f));
        }
    }

    /**
     * Catch presentation.
     *
     * @param override Shop cosmetic (null if none)
     */
    public void caught(
            Player player,
            FishDef fish,
            double sizeCm,
            Quality quality,
            boolean firstCatch,
            int intensity,
            boolean dialogEnabled,
            @Nullable CatchPresentationOverride override) {
        FishingPresets presets = presets();
        FishingPresets.Stage stage = presets.of(fish.grade()).caught();
        Location at = Locs.of(player);
        boolean replaced =
                override != null
                        && override.present(
                                player,
                                new CatchPresentationOverride.CatchPresentation(
                                        fish, sizeCm, quality, firstCatch, at, intensity));
        // Reel-in (at location) + grade catch sound (2D, self). The grade sound plays even if a
        // cosmetic changes the presentation ("4. Fishing presentation" principle 1)
        step(at, "reel_in");
        HarborSounds.play(player, stage.sound(), options(stage));
        if (stage.has("flop") && presets.shows(intensity, "flop")) {
            step(at, "flop");
        }
        if (!replaced) {
            particle(at.clone().add(0, 1, 0), stage.particle(), stage.particleCount());
            if (stage.has("pillar") && presets.shows(intensity, "pillar")) {
                pillar(at, stage.particle());
            }
            if (stage.has("firework") && presets.shows(intensity, "firework")) {
                particle(at.clone().add(0, 2.5, 0), "firework", 80);
            }
            if (stage.has("flop") && presets.shows(intensity, "flop")) {
                flop(player, fish, sizeCm);
            }
        }
        String size = String.format(Locale.ROOT, "%.1f", sizeCm);
        if (stage.has("nearby") && presets.shows(intensity, "nearby")) {
            Component line =
                    api.messages()
                            .get(
                                    "fishing.present.nearby",
                                    Placeholder.unparsed("player", player.getName()),
                                    Placeholder.unparsed("name", fish.name()),
                                    Placeholder.unparsed("size", size));
            World world = player.getWorld();
            for (Player other : world.getPlayers()) {
                if (!other.equals(player)
                        && Locs.of(other).distanceSquared(at) <= NEARBY_RADIUS * NEARBY_RADIUS) {
                    other.sendMessage(line);
                }
            }
        }
        if (dialogEnabled && stage.has("dialog") && presets.shows(intensity, "dialog")) {
            api.dialogs()
                    .notice(
                            player,
                            api.messages()
                                    .get(
                                            "item.name." + fish.grade().key(),
                                            Placeholder.unparsed("name", fish.name())),
                            List.of(
                                    api.messages()
                                            .get(
                                                    "fishing.present.card",
                                                    Placeholder.unparsed("size", size),
                                                    Placeholder.unparsed(
                                                            "quality", quality.display())),
                                    firstCatch
                                            ? api.messages().get("fishing.present.first_catch")
                                            : Component.empty()));
        }
    }

    /**
     * Flopping in the hand ("4. Fishing presentation & boss fights" chapter 1, common~advanced "the
     * fish jumps up onto your hand"). If the species rig rig/fish/<name> has a flop animation, it
     * is spawned in front of the player (at hand height), played for FLOP_TICKS and removed.
     * Skipped if there is no rig or animation. Visible only to the player and nearby players.
     */
    private void flop(Player player, FishDef fish, double sizeCm) {
        RigService rigs = api.rigs();
        // The species rig follows the model name (FishDef.model). Otherwise the old rule (species
        // ID name)
        RigId rig = FishRigs.rigOf(fish);
        if (rigs.definition(rig).isEmpty()) {
            rig = FishRigs.byId(fish);
        }
        Optional<RigDef> def = rigs.definition(rig);
        if (def.isEmpty() || def.get().animation(FLOP).isEmpty()) {
            return;
        }
        // Scale to the caught size (resource pack guide: scale = size (m) ÷ model length)
        double scale = FishRigs.fitScale(def.get(), sizeCm / 100.0);
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();
        Location at = eye.clone().add(dir.multiply(0.6)).add(0, -0.45, 0);
        at.setYaw(eye.getYaw() + 90f); // Lay the body on its side for display
        at.setPitch(0);
        List<Player> viewers = new ArrayList<>();
        viewers.add(player);
        for (Player other : player.getWorld().getPlayers()) {
            if (!other.equals(player)
                    && Locs.of(other).distanceSquared(at) <= FLOP_VIEW_RADIUS * FLOP_VIEW_RADIUS) {
                viewers.add(other);
            }
        }
        try {
            RigInstance instance =
                    rigs.spawn(
                            rig,
                            at,
                            RigOptions.privateTo(viewers)
                                    .withAnimation(FLOP)
                                    .withScale(scale)
                                    .withMoveTicks(0));
            var _ = tasks.later(FLOP_TICKS, instance::remove);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Skip if the rig cannot be spawned
        }
    }

    /** Whether to show the jump scene to nearby players too. */
    public boolean jumpViewers(int intensity) {
        return presets().shows(intensity, "jump_viewers");
    }

    public boolean shadows(int intensity) {
        return presets().shows(intensity, "shadow");
    }

    private void pillar(Location at, @Nullable String particleName) {
        Particle particle = resolve(particleName == null ? "end_rod" : particleName);
        if (particle == null) {
            return;
        }
        var task =
                tasks.timer(
                        0,
                        2,
                        () -> {
                            for (double y = 0; y < 6; y += 0.5) {
                                spawn(at.clone().add(0, y, 0), particle, 1, 0.05);
                            }
                        });
        var _ = tasks.later(PILLAR_TICKS, task::cancel);
    }

    /** Plays a fishing step sound (presets.yml sounds, "15. Sound" 4.1) at that location. */
    public void step(Location at, String step) {
        HarborSounds.playAt(at, presets().sound(step), SOUND);
    }

    /** Step sound key (for handling sounds directly, e.g. looping sounds). */
    public @Nullable String stepSound(String step) {
        return presets().sound(step);
    }

    private static HarborSounds.Options options(FishingPresets.Stage stage) {
        return SOUND.withFallback(stage.soundFallback());
    }

    private static void particle(Location at, @Nullable String name, int count) {
        if (name == null || count <= 0) {
            return;
        }
        Particle particle = resolve(name);
        if (particle != null) {
            spawn(at, particle, count, 0.4);
        }
    }

    private static void spawn(Location at, Particle particle, int count, double spread) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        try {
            world.spawnParticle(particle, at, count, spread, spread, spread, 0.02);
        } catch (IllegalArgumentException e) {
            // Skip particles that need data (dust etc.)
        }
    }

    /** Particle name (lowercase key or enum name). Null if not found. */
    public static @Nullable Particle resolve(String name) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
