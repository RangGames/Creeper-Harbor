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

/** 파티클·폭죽 도우미 (코스메틱 연출). 콘텐츠의 파티클 키는 바닐라 키(minecraft: 생략 가능). 메인 스레드 전용. */
final class Fx {

    /** 연출용 폭죽 표시 (피해를 막는다). */
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

    /** 파티클 하나를 뿌린다. 데이터가 필요한 파티클은 dust(색)만 지원하고 나머지는 건너뛴다. */
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

    /** 폭죽을 바로 터뜨린다 (피해 없음: 엔티티를 만들자마자 터뜨림). */
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
