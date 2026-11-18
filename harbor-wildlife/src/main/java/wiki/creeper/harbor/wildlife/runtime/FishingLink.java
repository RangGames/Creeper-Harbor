package wiki.creeper.harbor.wildlife.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.fishing.api.MinigameResult;
import wiki.creeper.harbor.fishing.api.ShadowDriver;
import wiki.creeper.harbor.fishing.api.event.FishBiteEvent;
import wiki.creeper.harbor.fishing.api.event.FishingCastEvent;
import wiki.creeper.harbor.fishing.api.event.FishingSessionEndEvent;
import wiki.creeper.harbor.fishing.api.event.MinigameEndEvent;
import wiki.creeper.harbor.wildlife.api.Medium;
import wiki.creeper.harbor.wildlife.api.WildState;
import wiki.creeper.harbor.wildlife.content.WildlifeBalance;
import wiki.creeper.harbor.wildlife.sim.FishOut;
import wiki.creeper.harbor.wildlife.sim.MediumField;

/**
 * 먹이 · 미끼 반응 (Fishing 연결, '17-3' 9.4). Wildlife → Fishing 한 방향: Fishing 이벤트를 듣고 Fishing
 * api(claimShadow·ShadowDriver)만 부른다. 야생 개체는 출현 판정에 영향을 주지 않는다 (판정 결과가 연출을 고른다).
 *
 * <ol>
 *   <li>FishingCastEvent: 같은 매질 반경 10블록 안 CRUISE 개체 중 최대 2마리가 성향별 확률로 APPROACH
 *   <li>접근 개체 중 판정된 어종과 같은 개체가 있으면 claimShadow로 대기 중 그림자를 맡고, 입질 때 bite 후 찌 아래로 잠겨 Fishing 그림자가 같은
 *       자리에서 이어받는다
 *   <li>FishBiteEvent: 다른 접근 개체는 "빼앗긴" 모습으로 dart 후 CRUISE
 *   <li>MinigameEndEvent · FishingSessionEndEvent: 접근 개체를 풀어 준다. 줄이 끊기면 주변 개체가 놀란다
 * </ol>
 */
final class FishingLink implements Listener {

    /** 캐스팅 하나에 다가온 개체. */
    private record Cast(Location hook, List<UUID> approachers, @Nullable UUID claimed) {}

    private final WildlifeKit kit;
    private final WildlifeManager manager;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final Map<UUID, Cast> casts = new HashMap<>();

    FishingLink(WildlifeKit kit, WildlifeManager manager) {
        this.kit = kit;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onCast(FishingCastEvent event) {
        Player player = event.getPlayer();
        release(player.getUniqueId(), false);
        Location hook = event.hook();
        World w = hook.getWorld();
        if (w == null) {
            return;
        }
        WildlifeBalance bal = kit.balance();
        MediumField field = kit.grid.field(w.getName());
        Medium medium =
                field.is(Medium.LAVA, hook.getX(), hook.getY() - 0.5, hook.getZ())
                        ? Medium.LAVA
                        : Medium.WATER;
        List<FishOut> near =
                new ArrayList<>(
                        manager.frame()
                                .nearby(
                                        w.getName(),
                                        hook.getX(),
                                        hook.getY(),
                                        hook.getZ(),
                                        bal.baitRadius()));
        Collections.shuffle(near, new java.util.Random(random.nextLong()));
        List<UUID> busy = busyFish();
        List<FishOut> chosen = new ArrayList<>();
        for (FishOut o : near) {
            if (chosen.size() >= bal.baitMax()) {
                break;
            }
            if (o.hidden()
                    || busy.contains(o.id())
                    || (o.state() != WildState.CRUISE && o.state() != WildState.SCHOOL)
                    || o.profile().medium() == Medium.FLOOR
                    || o.profile().medium().cell() != medium) {
                continue;
            }
            if (random.nextDouble() < o.profile().groupDef().baitChance()) {
                chosen.add(o);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        UUID claimed = null;
        UUID angler = player.getUniqueId();
        Vec3 hookPos = new Vec3(hook.getX(), hook.getY(), hook.getZ());
        double seconds = event.waitTicks() / 20.0;
        List<UUID> ids = new ArrayList<>();
        for (FishOut o : chosen) {
            ids.add(o.id());
            UUID id = o.id();
            if (claimed == null && o.profile().fish().id().equals(event.fish().id())) {
                if (event.claimShadow(new WildShadow(id, angler, hook))) {
                    claimed = id;
                    // Fishing도 ShadowDriver.approach를 부른다 (같은 값으로 다시 맞출 뿐)
                    manager.engine()
                            .submit(world -> world.approach(id, hookPos, seconds, true, angler));
                    continue;
                }
            }
            manager.engine().submit(world -> world.approach(id, hookPos, seconds, false, angler));
        }
        casts.put(angler, new Cast(hook, List.copyOf(ids), claimed));
    }

    private List<UUID> busyFish() {
        List<UUID> busy = new ArrayList<>();
        for (Cast c : casts.values()) {
            busy.addAll(c.approachers());
        }
        return busy;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBite(FishBiteEvent event) {
        Cast c = casts.get(event.getPlayer().getUniqueId());
        if (c == null) {
            return;
        }
        for (UUID id : c.approachers()) {
            if (!id.equals(c.claimed())) {
                manager.engine().submit(world -> world.stolen(id));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMinigameEnd(MinigameEndEvent event) {
        release(event.getPlayer().getUniqueId(), event.result() == MinigameResult.LINE_BROKE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSessionEnd(FishingSessionEndEvent event) {
        release(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        release(event.getPlayer().getUniqueId(), false);
    }

    private void release(UUID angler, boolean lineBroke) {
        Cast c = casts.remove(angler);
        if (c == null) {
            return;
        }
        manager.engine().submit(world -> world.releaseAngler(angler, lineBroke));
        World w = c.hook().getWorld();
        if (lineBroke && w != null) {
            Vec3 at = new Vec3(c.hook().getX(), c.hook().getY(), c.hook().getZ());
            double r = kit.balance().lineBreakRadius();
            String name = w.getName();
            manager.engine().submit(world -> world.threatAt(name, at, r));
        }
    }

    int activeCasts() {
        return casts.size();
    }

    /** 대기 중 그림자 대행 (Fishing api SPI). 모든 메서드는 메인 스레드에서 불리고, 계산 스레드에는 요청만 넣는다. 위치는 마지막 계산 면 기준. */
    private final class WildShadow implements ShadowDriver {

        private final UUID fish;
        private final UUID angler;
        private Location last;

        WildShadow(UUID fish, UUID angler, Location hook) {
            this.fish = fish;
            this.angler = angler;
            this.last = hook.clone();
        }

        @Override
        public void approach(Location hook, int ticks) {
            Vec3 h = new Vec3(hook.getX(), hook.getY(), hook.getZ());
            double seconds = ticks / 20.0;
            manager.engine().submit(world -> world.approach(fish, h, seconds, true, angler));
        }

        @Override
        public void bite() {
            manager.engine().submit(world -> world.bite(fish));
        }

        @Override
        public Location location() {
            FishOut o = manager.frame().get(fish);
            World w = last.getWorld();
            if (o != null && w != null) {
                last = new Location(w, o.x(), o.y(), o.z(), o.yaw(), o.pitch());
            }
            return last.clone();
        }

        @Override
        public boolean isValid() {
            return manager.alive(fish);
        }

        @Override
        public void release(boolean caught) {
            manager.engine().submit(world -> world.release(fish, caught, null));
        }
    }
}
