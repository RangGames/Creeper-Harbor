package wiki.creeper.harbor.fishing.present;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.core.api.RigService;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.Locs;
import wiki.creeper.harbor.fishing.api.CatchPresentationOverride;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * 등급 연출 프리셋 재생 (fishing/presets.yml, '4. 낚시 연출 & 보스전' 1장). 입질과 포획 단계의 효과음·파티클·어둠·타이틀·빛기둥·근처 알림·결과
 * Dialog를 연출 강도 설정에 맞춰 보여 준다. 포획 연출은 Shop 코스메틱(CatchPresentationOverride)이 있으면 그쪽이 먼저 받는다. 메인 스레드
 * 전용.
 */
public final class CatchPresenter {

    private static final double NEARBY_RADIUS = 32;
    private static final int PILLAR_TICKS = 40;
    private static final String FLOP = "flop";
    private static final int FLOP_TICKS = 30;
    private static final double FLOP_VIEW_RADIUS = 16;

    private final HarborApi api;
    private final Tasks tasks;

    public CatchPresenter(HarborApi api, Tasks tasks) {
        this.api = api;
        this.tasks = tasks;
    }

    private FishingPresets presets() {
        return api.content().snapshot().single(FishingPresets.TYPE);
    }

    /** 입질 연출 (찌 위치). */
    public void bite(Player player, FishDef fish, Location hook, int intensity) {
        FishingPresets presets = presets();
        FishingPresets.Stage stage = presets.of(fish.grade()).bite();
        sound(player, stage.sound());
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
            // 클라이언트 화면 흔들림 API가 없어 피격 흔들림 대신 짧은 진동음으로 표시한다
            player.playSound(
                    Sound.sound(
                            Key.key("minecraft:entity.warden.heartbeat"),
                            Sound.Source.PLAYER,
                            1f,
                            1.4f));
        }
    }

    /**
     * 포획 연출.
     *
     * @param override Shop 코스메틱 (null이면 없음)
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
        if (!replaced) {
            sound(player, stage.sound());
            particle(at.clone().add(0, 1, 0), stage.particle(), stage.particleCount());
            if (stage.has("pillar") && presets.shows(intensity, "pillar")) {
                pillar(at, stage.particle());
            }
            if (stage.has("firework") && presets.shows(intensity, "firework")) {
                particle(at.clone().add(0, 2.5, 0), "firework", 80);
            }
            if (stage.has("flop") && presets.shows(intensity, "flop")) {
                flop(player, fish);
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
     * 손 위 퍼덕임 ('4. 낚시 연출 & 보스전' 1장 일반~고급 '물고기가 손 위로 튀어 오름'). 어종 리그 rig/fish/<이름>에 flop 동작이 있으면 플레이어
     * 앞(손 높이)에 띄워 FLOP_TICKS 동안 재생하고 지운다. 리그·동작이 없으면 생략한다. 본인과 근처 플레이어에게만 보인다.
     */
    private void flop(Player player, FishDef fish) {
        RigId rig =
                new RigId(
                        new HarborId(
                                fish.id().id().namespace(), "rig/fish/" + fish.id().id().name()));
        RigService rigs = api.rigs();
        boolean hasFlop =
                rigs.definition(rig).map(d -> d.animation(FLOP).isPresent()).orElse(false);
        if (!hasFlop) {
            return;
        }
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-6) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();
        Location at = eye.clone().add(dir.multiply(0.6)).add(0, -0.45, 0);
        at.setYaw(eye.getYaw() + 90f); // 몸을 옆으로 눕혀 보여준다
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
                            RigOptions.privateTo(viewers).withAnimation(FLOP).withMoveTicks(0));
            var _ = tasks.later(FLOP_TICKS, instance::remove);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 리그를 띄우지 못하면 생략
        }
    }

    /** 점프 장면을 근처 플레이어에게도 보여 줄지. */
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

    private static void sound(Player player, @Nullable String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            player.playSound(Sound.sound(Key.key(key), Sound.Source.PLAYER, 1f, 1f));
        } catch (IllegalArgumentException e) {
            // 키 형식이 틀리면 생략
        }
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
            // 데이터가 필요한 파티클(dust 등)은 생략
        }
    }

    /** 파티클 이름 (소문자 키 또는 enum 이름). 없으면 null. */
    public static @Nullable Particle resolve(String name) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
