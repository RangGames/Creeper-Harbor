package wiki.creeper.harbor.fishing.present;

import io.papermc.paper.datacomponent.item.SwingAnimation;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
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
    private final BobberEffects bobber;
    private final MeasurePresenter measure;
    private final CatchCard card;
    private final ScreenEffects screen;

    public CatchPresenter(Plugin plugin, HarborApi api, Tasks tasks) {
        this.api = api;
        this.tasks = tasks;
        this.bobber = new BobberEffects(plugin, tasks);
        this.measure = new MeasurePresenter(plugin, api, tasks);
        this.card = new CatchCard(api);
        this.screen = new ScreenEffects(tasks);
    }

    // ------------------------------------------------------------------ Cast · wait (1.3)

    /** Bobber in flight: cast line trail (one tick). */
    public void trail(Location bobber, int intensity) {
        FishingPresets presets = presets();
        if (presets.shows(intensity, "trail")) {
            this.bobber.trail(bobber, presets.castTrail());
        }
    }

    /** Bobber landing: landing sound and splash. */
    public void land(Location at, String step) {
        step(at, step);
        bobber.land(at);
    }

    /**
     * Waiting ambience of the rolled grade (legendary darkened water and low rumble), every {@link
     * FishingPresets#WAIT_INTERVAL_TICKS} ticks.
     */
    public void waiting(Player player, FishDef fish, Location hook, int ticks, int intensity) {
        FishingPresets presets = presets();
        FishingPresets.Stage stage = presets.of(fish.grade()).waiting();
        if (ticks % FishingPresets.WAIT_INTERVAL_TICKS != 0
                || stage.equals(FishingPresets.Stage.NONE)
                || !presets.shows(intensity, "ambience")) {
            return;
        }
        bobber.waiting(player, hook, stage);
    }

    /** Removes the bite mark (hooked, missed or cancelled). */
    public void clearMark(Player player) {
        bobber.clearMark(player);
    }

    /**
     * Hooking: forced hand swing ("4. Fishing presentation" 1.3, 26.3 swing animation; heavier
     * motions are configured in presets.yml hook_swing).
     */
    public void swing(Player player, double rodSwingTicks) {
        FishingPresets.HookSwing swing = presets().hookSwing();
        if (!swing.enabled()) {
            return;
        }
        // Heavier rods swing longer (rod item stat swing_ticks, proposed)
        int ticks = rodSwingTicks > 0 ? (int) Math.round(rodSwingTicks) : swing.ticks();
        player.swingHand(
                EquipmentSlot.HAND,
                SwingAnimation.swingAnimation()
                        .type(
                                swing.type().equals("stab")
                                        ? SwingAnimation.Animation.STAB
                                        : SwingAnimation.Animation.WHACK)
                        .duration(ticks)
                        .build());
    }

    /** Rod bend levels for the tension model (0 = off) at this intensity. */
    public int rodBendSteps(int intensity) {
        FishingPresets presets = presets();
        return presets.shows(intensity, "rod_bend") ? presets.rodBendSteps() : 0;
    }

    public void forget(Player player) {
        bobber.clearMark(player);
        screen.clear(player);
        card.forget(player.getUniqueId());
    }

    public void shutdown() {
        bobber.clearAll();
    }

    private FishingPresets presets() {
        return api.content().snapshot().single(FishingPresets.TYPE);
    }

    /**
     * Bite presentation (bobber location).
     *
     * @param windowTicks bite window, how long the "!" mark stays
     */
    public void bite(Player player, FishDef fish, Location hook, int intensity, int windowTicks) {
        FishingPresets presets = presets();
        FishingPresets.GradePreset preset = presets.of(fish.grade());
        FishingPresets.Stage stage = preset.bite();
        if (presets.shows(intensity, "mark")) {
            bobber.mark(player, hook, preset, windowTicks);
        }
        if (presets.shows(intensity, "screen")) {
            screen.play(player, stage);
        }
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
     * @param capped the quality was capped by the job tier ("missed big fish" on the action bar)
     * @param override Shop cosmetic (null if none)
     */
    public void caught(
            Player player,
            FishDef fish,
            double sizeCm,
            Quality quality,
            boolean firstCatch,
            boolean capped,
            int intensity,
            boolean dialogEnabled,
            @Nullable CatchPresentationOverride override) {
        FishingPresets presets = presets();
        FishingPresets.GradePreset preset = presets.of(fish.grade());
        FishingPresets.Stage stage = preset.caught();
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
                pillar(at, stage.particle(), preset.markColor());
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
        boolean dialog = dialogEnabled && stage.has("dialog") && presets.shows(intensity, "dialog");
        // Measure step: action bar / title / measuring animation, then the result card so it does
        // not cover the animation
        measure.reveal(
                player,
                fish,
                sizeCm,
                quality,
                capped,
                preset.measure(),
                presets.shows(intensity, "tape"),
                () -> {
                    if (dialog && player.isOnline()) {
                        card.show(player, fish, sizeCm, quality, firstCatch);
                    }
                });
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

    /** Light pillar in the grade color (mark_color dust) with the preset particle. */
    private void pillar(Location at, @Nullable String particleName, String color) {
        Particle particle = resolve(particleName == null ? "end_rod" : particleName);
        Particle.DustOptions dust = new Particle.DustOptions(Holograms.color(color), 1.6f);
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        var task =
                tasks.timer(
                        0,
                        2,
                        () -> {
                            for (double y = 0; y < 6; y += 0.5) {
                                Location point = at.clone().add(0, y, 0);
                                world.spawnParticle(
                                        Particle.DUST, point, 2, 0.12, 0.05, 0.12, 0, dust);
                                if (particle != null) {
                                    spawn(point, particle, 1, 0.05);
                                }
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
