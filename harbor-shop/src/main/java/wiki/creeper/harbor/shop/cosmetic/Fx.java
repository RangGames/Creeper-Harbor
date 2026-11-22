package wiki.creeper.harbor.shop.cosmetic;

import java.util.List;
import java.util.Locale;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Particle and firework helpers (cosmetic effects). Content particle keys are vanilla keys
 * (minecraft: optional). Main thread only.
 */
final class Fx {

    /** Marks effect fireworks (prevents damage). */
    static final NamespacedKey FX_TAG = new NamespacedKey("harbor", "shop_fx");

    private Fx() {}

    static @Nullable Particle particle(String key) {
        NamespacedKey nk = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
        return nk == null ? null : Registry.PARTICLE_TYPE.get(nk);
    }

    static Color color(String hex) {
        try {
            return Color.fromRGB(Integer.parseInt(hex.substring(1), 16));
        } catch (RuntimeException e) {
            return Color.WHITE;
        }
    }

    /**
     * Spawns one particle. Of particles that need data, only dust (color) is supported; others are
     * skipped.
     */
    static void spawn(
            String key, @Nullable String hex, Location at, int count, double spread, int radius) {
        Particle particle = particle(key);
        if (particle == null) {
            return;
        }
        Class<?> data = particle.getDataType();
        var builder =
                particle.builder()
                        .location(at)
                        .count(count)
                        .offset(spread, spread, spread)
                        .receivers(radius, true);
        if (data == Particle.DustOptions.class) {
            builder.data(new Particle.DustOptions(color(hex == null ? "#ffffff" : hex), 1.2f));
        } else if (data != Void.class) {
            return;
        }
        builder.spawn();
    }

    /**
     * Detonates a firework immediately (no damage: detonated right after the entity is created).
     */
    static void firework(Location at, String type, List<String> colors) {
        FireworkEffect.Type shape;
        try {
            shape = FireworkEffect.Type.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            shape = FireworkEffect.Type.BALL;
        }
        FireworkEffect effect =
                FireworkEffect.builder()
                        .with(shape)
                        .withColor(colors.stream().map(Fx::color).toList())
                        .flicker(true)
                        .build();
        at.getWorld()
                .spawn(
                        at,
                        Firework.class,
                        fw -> {
                            FireworkMeta meta = fw.getFireworkMeta();
                            meta.addEffect(effect);
                            meta.setPower(0);
                            fw.setFireworkMeta(meta);
                            fw.getPersistentDataContainer()
                                    .set(FX_TAG, PersistentDataType.BOOLEAN, true);
                            fw.detonate();
                        });
    }
}
