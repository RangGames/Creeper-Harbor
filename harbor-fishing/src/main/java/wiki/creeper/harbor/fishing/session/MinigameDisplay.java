package wiki.creeper.harbor.fishing.session;

import static wiki.creeper.harbor.fishing.session.FishingManager.loc;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.WeatherType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.core.api.WorldBorderService;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.present.CatchPresenter;

/** 미니게임 입력 해석과 화면·소리 (텐션 게이지, 보스바 색, 릴 소리, 경고 테두리, 전설 하늘 연출, 이동 잠금). 메인 스레드 전용. */
final class MinigameDisplay {

    private static final int GAUGE_CELLS = 20;
    private static final float LOOK_THRESHOLD = 12f;
    private static final double JUMP_VIEW_RADIUS = 24;

    private final HarborApi api;
    private final Tasks tasks;
    private final CatchPresenter presenter;
    private final CastContext ctx;
    private final NamespacedKey moveLock;

    MinigameDisplay(
            Plugin plugin, HarborApi api, Tasks tasks, CatchPresenter presenter, CastContext ctx) {
        this.api = api;
        this.tasks = tasks;
        this.presenter = presenter;
        this.ctx = ctx;
        this.moveLock = new NamespacedKey(plugin, "minigame_lock");
    }

    /**
     * 방향 입력: 이동 키 또는 시점 회전 (설정). 배 위에서도 A·D 입력이 그대로 오므로 키를 쓴다 (예전에는 배 위를 시점 회전으로 바꿨는데, 배가 돌면 시점도 같이
     * 돌아 시작 방향과의 차이가 계속 남아 한쪽으로 굳었다).
     */
    Minigame.Input input(
            Player player,
            boolean sneak,
            boolean left,
            boolean right,
            boolean look,
            float baseYaw) {
        if (look) {
            float delta = wrap(loc(player).getYaw() - baseYaw);
            return new Minigame.Input(sneak, delta < -LOOK_THRESHOLD, delta > LOOK_THRESHOLD);
        }
        return new Minigame.Input(sneak, left, right);
    }

    private static float wrap(float degrees) {
        float d = degrees % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    /**
     * 릴 감기 반복 소리: Shift를 누르고 있는 동안 켜고, 떼면 끊는다 ('15. 사운드' 2장 fishing.reel_loop). 플레이어를 따라가며 근처에도
     * 들린다.
     */
    HarborSounds.Loop reelSound(Player player, boolean reeling, HarborSounds.Loop current) {
        if (reeling && !current.active()) {
            return HarborSounds.loopFrom(
                    tasks, player, presenter.stepSound("reel_loop"), CatchPresenter.SOUND);
        }
        if (!reeling && current.active()) {
            current.stop();
            return HarborSounds.Loop.NONE;
        }
        return current;
    }

    /** 찌가 떨어진 곳: 용암이면 용암 착수, 얼음 구멍(옆이나 위가 얼음)이면 얼음 구멍 착수, 아니면 일반 착수 ('15. 사운드' 4.1). */
    static String landStep(Location at) {
        Block block = at.getBlock();
        if (block.getType() == Material.LAVA) {
            return "land_lava";
        }
        for (BlockFace face :
                List.of(
                        BlockFace.UP,
                        BlockFace.NORTH,
                        BlockFace.SOUTH,
                        BlockFace.EAST,
                        BlockFace.WEST)) {
            if (Tag.ICE.isTagged(block.getRelative(face).getType())) {
                return "land_ice";
            }
        }
        return "land";
    }

    /** 텐션 0.85 이상이면 Core WorldBorderService에 경고를 요청해 화면 가장자리를 붉게 한다. */
    void warnBorder(
            Player player,
            double tension,
            boolean active,
            java.util.function.Consumer<Boolean> set) {
        boolean warn = tension >= ctx.balance().tensionWarn();
        if (warn && !active) {
            presenter.step(loc(player), "tension_warn");
            api.worldBorders()
                    .request(
                            player,
                            WorldBorderService.Priority.FISHING_TENSION,
                            WorldBorderService.Border.warning(loc(player)));
            set.accept(true);
        } else if (!warn && active) {
            api.worldBorders().clear(player, WorldBorderService.Priority.FISHING_TENSION);
            set.accept(false);
        }
    }

    /** 전설 3페이즈 하늘 연출 (개인 날씨·시간). */
    void phaseSky(Player player, int phase, int intensity, FishDef fish) {
        if (!fish.grade().isAtLeast(Grade.LEGENDARY)
                || !ctx.presets().shows(intensity, "darkness")) {
            return;
        }
        if (phase == 2) {
            player.setPlayerWeather(WeatherType.DOWNFALL);
        } else if (phase >= 3) {
            player.setPlayerTime(18000, false);
        }
        api.messages()
                .send(
                        player,
                        "fishing.minigame.phase",
                        Placeholder.unparsed("phase", String.valueOf(phase)));
    }

    List<Player> nearby(Player player) {
        Location at = loc(player);
        List<Player> result = new ArrayList<>();
        for (Player other : player.getWorld().getPlayers()) {
            if (!other.equals(player)
                    && loc(other).distanceSquared(at) <= JUMP_VIEW_RADIUS * JUMP_VIEW_RADIUS) {
                result.add(other);
            }
        }
        return result;
    }

    /**
     * 텐션 게이지 (20칸): 안전 구간 초록, 위험(0.85+) 빨강, 바늘 ▼. 물고기가 도망치는 쪽에 ◀◀ / ▶▶ (따라가고 있으면 초록, 아니면 빨강). 가운데로
     * 버틸 때 A·D를 누르고 있으면 누른 쪽에 빨간 화살표. 커스텀 폰트 글리프가 준비되면 Ui 글리프로 바꾼다.
     */
    static Component gauge(Minigame game, Minigame.Input input) {
        int needle = (int) Math.min(GAUGE_CELLS - 1, Math.floor(game.tension() * GAUGE_CELLS));
        Component bar = Component.empty();
        for (int i = 0; i < GAUGE_CELLS; i++) {
            double pos = (i + 0.5) / GAUGE_CELLS;
            NamedTextColor color =
                    pos >= 0.85
                            ? NamedTextColor.RED
                            : pos >= game.safeLow() && pos <= game.safeHigh()
                                    ? NamedTextColor.GREEN
                                    : NamedTextColor.DARK_GRAY;
            bar =
                    bar.append(
                            Component.text(
                                    i == needle ? "▼" : "|",
                                    i == needle ? NamedTextColor.WHITE : color));
        }
        NamedTextColor cue =
                game.followsDirection(input) ? NamedTextColor.GREEN : NamedTextColor.RED;
        boolean left =
                switch (game.direction()) {
                    case LEFT -> true;
                    case RIGHT -> false;
                    case CENTER -> input.left();
                };
        boolean right =
                switch (game.direction()) {
                    case LEFT -> false;
                    case RIGHT -> true;
                    case CENTER -> input.right();
                };
        return Component.text(left ? "◀◀ " : "    ", cue)
                .append(bar)
                .append(Component.text(right ? " ▶▶" : "    ", cue));
    }

    static BossBar.Color barColor(Grade grade) {
        return switch (grade) {
            case COMMON -> BossBar.Color.WHITE;
            case UNCOMMON -> BossBar.Color.GREEN;
            case RARE -> BossBar.Color.BLUE;
            case EPIC -> BossBar.Color.PURPLE;
            case LEGENDARY -> BossBar.Color.YELLOW;
            case MYTHIC -> BossBar.Color.RED;
        };
    }

    void lockMovement(Player player, boolean lock) {
        AttributeInstance speed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        speed.removeModifier(moveLock);
        if (lock) {
            speed.addTransientModifier(
                    new AttributeModifier(
                            moveLock, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
    }
}
