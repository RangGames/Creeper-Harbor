package wiki.creeper.harbor.world.ride;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Nautilus;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.Dialogs;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * 탈것 (docs 20 8.4 '탈것').
 *
 * <ul>
 *   <li>항구 비행선 (해피 가스트): 해금 지역 정류장끼리 노선. 그 지역을 배로 한 번 방문해야(region_visit) 노선이 열린다. 3분마다 출발(정각 기준),
 *       가스트 1마리에 4명, 넘치면 한 마리 더. AI를 끄고 서버가 경로(상승 → 순항 → 하강)를 따라 옮기며 비행 중 하차는 막는다. 경로 앞 3청크에 청크 티켓.
 *       서버가 꺼지면 탑승자를 도착지로 옮긴다. 제어 방식은 Phase 1 검증 항목
 *   <li>심해 탈것 (노틸러스): 잿빛 해구 잠수 정류장에서 무료 대여. 하차 후 30초 또는 해구를 벗어나면 회수. 노틸러스 갑옷이면 산소 소모 ×0.5
 *       (EnvironmentManager)
 * </ul>
 *
 * 모든 엔티티는 저장하지 않고(setPersistent false) PDC 태그로 남은 것을 지운다. 메인 스레드 전용.
 */
public final class RideManager implements Listener {

    private static final String AIRSHIP_SOUND = "harbor:travel.airship";

    private record Stop(WorldIndex.Entry region, RegionExt.Dock dock) {

        String key() {
            return region.id() + "#" + dock.id();
        }
    }

    private static final class Flight {
        final HappyGhast ghast;
        final List<Location> path;
        final Location arrival;
        int segment;
        final Set<Long> tickets = new java.util.HashSet<>();

        /** 비행 반복 소리 (travel.airship, '15. 사운드' 4.4). */
        HarborSounds.Loop sound = HarborSounds.Loop.NONE;

        Flight(HappyGhast ghast, List<Location> path, Location arrival) {
            this.ghast = ghast;
            this.path = path;
            this.arrival = arrival;
        }
    }

    private static final class Rental {
        final Nautilus nautilus;
        final RegionId region;
        int idle;

        Rental(Nautilus nautilus, RegionId region) {
            this.nautilus = nautilus;
            this.region = region;
        }
    }

    private final WorldKit kit;
    private final RegionManager regions;

    /** (출발 정류장 키 → 목적 지역 → 대기 승객). */
    private final Map<String, Map<RegionId, List<UUID>>> queue = new LinkedHashMap<>();

    private final List<Flight> flights = new ArrayList<>();
    private final Map<UUID, Rental> rentals = new HashMap<>();

    /** 퇴장 중이라 비행 중에도 내리게 둘 플레이어 (onQuit 동안만). */
    private final Set<UUID> leaving = new java.util.HashSet<>();

    public RideManager(WorldKit kit, RegionManager regions) {
        this.kit = kit;
        this.regions = regions;
    }

    public void start() {
        var _ = kit.tasks().timer(1, 1, this::fly);
        var _ = kit.tasks().timer(20, 20, this::second);
        for (World world : Bukkit.getWorlds()) {
            for (Entity e : world.getEntities()) {
                if (tagged(e)) {
                    e.remove();
                }
            }
        }
    }

    public void shutdown() {
        for (Flight f : List.copyOf(flights)) {
            land(f);
        }
        rentals.values().forEach(r -> r.nautilus.remove());
        rentals.clear();
    }

    private boolean tagged(Entity e) {
        return e.getPersistentDataContainer().has(kit.key("ride"), PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ 비행선

    private List<Stop> stops(RegionExt.DockKind kind) {
        List<Stop> result = new ArrayList<>();
        for (WorldIndex.Entry e : kit.index().regions()) {
            for (RegionExt.Dock d : e.ext().docks()) {
                if (d.kind() == kind) {
                    result.add(new Stop(e, d));
                }
            }
        }
        return result;
    }

    private Optional<Stop> nearStop(Player player, RegionExt.DockKind kind) {
        Location at = WorldKit.loc(player);
        String world = kit.worldId(at);
        double r = kit.balance().airshipBoardRadius();
        for (Stop s : stops(kind)) {
            RegionExt.Point p = s.dock().at();
            if (s.region().def().world().equals(world)
                    && at.distanceSquared(new Location(at.getWorld(), p.x(), p.y(), p.z()))
                            <= r * r) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** 이 플레이어가 지금 정류장에서 갈 수 있는 노선 (같은 월드, 방문한 지역, 탈것 조건 외 진입 조건 충족). */
    private List<WorldIndex.Entry> destinations(Player player, Stop from) {
        List<WorldIndex.Entry> result = new ArrayList<>();
        for (Stop s : stops(RegionExt.DockKind.AIRSHIP)) {
            if (s.region().id().equals(from.region().id())
                    || !s.region().def().world().equals(from.region().def().world())
                    || !regions.hasVisited(player.getUniqueId(), s.region().id())
                    || !regions.status(player, s.region(), true).allowed()) {
                continue;
            }
            result.add(s.region());
        }
        return result;
    }

    /** /비행선 [지역]: 정류장 근처에서 노선을 골라 다음 출발을 기다린다. */
    public void board(Player player, @Nullable RegionId target) {
        Optional<Stop> from = nearStop(player, RegionExt.DockKind.AIRSHIP);
        if (from.isEmpty()) {
            kit.send(player, "world.airship.not_at_stop");
            return;
        }
        List<WorldIndex.Entry> options = destinations(player, from.get());
        if (options.isEmpty()) {
            kit.send(player, "world.airship.no_route");
            return;
        }
        if (target == null) {
            List<Dialogs.Choice> choices = new ArrayList<>();
            for (WorldIndex.Entry e : options) {
                choices.add(new Dialogs.Choice(Component.text(e.def().name()), Component.empty()));
            }
            kit.api()
                    .dialogs()
                    .choose(
                            player,
                            kit.api().messages().get("world.airship.title"),
                            List.of(kit.api().messages().get("world.airship.choose")),
                            choices,
                            2,
                            i -> enqueue(player, from.get(), options.get(i)));
            return;
        }
        Optional<WorldIndex.Entry> dest =
                options.stream().filter(e -> e.id().equals(target)).findFirst();
        if (dest.isEmpty()) {
            kit.send(player, "world.airship.locked");
            return;
        }
        enqueue(player, from.get(), dest.get());
    }

    private void enqueue(Player player, Stop from, WorldIndex.Entry dest) {
        UUID uuid = player.getUniqueId();
        queue.values().forEach(m -> m.values().forEach(l -> l.remove(uuid)));
        queue.computeIfAbsent(from.key(), k -> new LinkedHashMap<>())
                .computeIfAbsent(dest.id(), k -> new ArrayList<>())
                .add(uuid);
        long interval = Math.max(10, kit.balance().airshipIntervalSeconds());
        long now = Instant.now().getEpochSecond();
        long wait = interval - (now % interval);
        kit.send(
                player,
                "world.airship.queued",
                Placeholder.parsed("name", dest.def().name()),
                Placeholder.unparsed("seconds", String.valueOf(wait)));
    }

    /** 매초: 출발 시각이면 대기 승객을 태운다, 노틸러스 회수. */
    private void second() {
        long interval = Math.max(10, kit.balance().airshipIntervalSeconds());
        if (Instant.now().getEpochSecond() % interval == 0) {
            depart();
        }
        rentals();
    }

    private void depart() {
        WorldBalance b = kit.balance();
        double radius = b.airshipBoardRadius();
        for (Stop from : stops(RegionExt.DockKind.AIRSHIP)) {
            Map<RegionId, List<UUID>> routes = queue.remove(from.key());
            World world = kit.bukkitWorld(from.region().def().world());
            if (routes == null || world == null) {
                continue;
            }
            RegionExt.Point o = from.dock().at();
            Location origin = new Location(world, o.x(), o.y(), o.z(), o.yaw(), 0);
            routes.forEach(
                    (destId, uuids) -> {
                        Optional<Stop> dest =
                                stops(RegionExt.DockKind.AIRSHIP).stream()
                                        .filter(s -> s.region().id().equals(destId))
                                        .findFirst();
                        if (dest.isEmpty()) {
                            return;
                        }
                        List<Player> riders = new ArrayList<>();
                        for (UUID u : uuids) {
                            Player p = Bukkit.getPlayer(u);
                            if (p != null
                                    && p.getWorld().equals(world)
                                    && WorldKit.loc(p).distanceSquared(origin)
                                            <= radius * radius * 4) {
                                riders.add(p);
                            }
                        }
                        int capacity = Math.max(1, b.airshipCapacity());
                        for (int i = 0; i < riders.size(); i += capacity) {
                            launch(
                                    origin,
                                    dest.get(),
                                    riders.subList(i, Math.min(riders.size(), i + capacity)),
                                    b);
                        }
                    });
        }
    }

    private void launch(Location origin, Stop dest, List<Player> riders, WorldBalance b) {
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        RegionExt.Point d = dest.dock().at();
        Location arrival = new Location(world, d.x(), d.y(), d.z(), d.yaw(), 0);
        double altitude =
                Math.max(b.airshipAltitude(), Math.max(origin.getY(), arrival.getY()) + 20);
        List<Location> path = new ArrayList<>();
        path.add(origin.clone().add(0, 2, 0));
        path.add(new Location(world, origin.getX(), altitude, origin.getZ()));
        path.add(new Location(world, arrival.getX(), altitude, arrival.getZ()));
        path.add(arrival.clone().add(0, 2, 0));
        HappyGhast ghast =
                world.spawn(
                        path.getFirst(),
                        HappyGhast.class,
                        g -> {
                            g.setPersistent(false);
                            g.setAI(false);
                            g.setInvulnerable(true);
                            g.setSilent(true);
                            g.getPersistentDataContainer()
                                    .set(kit.key("ride"), PersistentDataType.BYTE, (byte) 1);
                            EntityEquipment eq = g.getEquipment();
                            eq.setItem(EquipmentSlot.BODY, ItemStack.of(Material.WHITE_HARNESS));
                        });
        for (Player p : riders) {
            ghast.addPassenger(p);
            kit.send(
                    p,
                    "world.airship.departed",
                    Placeholder.parsed("name", dest.region().def().name()));
        }
        Flight flight = new Flight(ghast, path, arrival);
        flight.sound =
                HarborSounds.loopFrom(
                        kit.tasks(),
                        ghast,
                        AIRSHIP_SOUND,
                        HarborSounds.Options.DEFAULT.withSource(Sound.Source.NEUTRAL));
        flights.add(flight);
    }

    /** 매 틱: 경로를 따라 옮긴다 (텔레포트, 승객은 함께 간다). */
    private void fly() {
        double speed = kit.balance().airshipSpeed();
        for (Flight f : List.copyOf(flights)) {
            if (!f.ghast.isValid()) {
                flights.remove(f);
                f.sound.stop();
                releaseTickets(f);
                continue;
            }
            if (f.segment >= f.path.size() - 1) {
                land(f);
                continue;
            }
            Location at = WorldKit.loc(f.ghast);
            Location next = f.path.get(f.segment + 1);
            Vector delta = next.toVector().subtract(at.toVector());
            double len = delta.length();
            Location to;
            if (len <= speed) {
                to = next.clone();
                f.segment++;
            } else {
                to = at.clone().add(delta.multiply(speed / len));
            }
            Vector flat = next.toVector().subtract(at.toVector()).setY(0);
            if (flat.lengthSquared() > 1e-4) {
                to.setDirection(flat);
                to.setPitch(0);
            }
            // 텔레포트가 거부되면 (월드 경계·이벤트 취소 등) 비행선이 제자리에 멈추고 탑승자는 내릴 수도 이동할 수도 없다:
            // 그 자리에 내려 준다
            if (!f.ghast.teleport(to)) {
                land(f);
                return;
            }
            ticket(f, to, flat);
        }
    }

    private void ticket(Flight f, Location at, Vector forward) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        Vector dir =
                forward.lengthSquared() > 1e-4
                        ? forward.clone().normalize().multiply(16)
                        : new Vector();
        Set<Long> wanted = new java.util.HashSet<>();
        for (int i = 0; i <= 3; i++) {
            Location p = at.clone().add(dir.clone().multiply(i));
            int cx = p.getBlockX() >> 4;
            int cz = p.getBlockZ() >> 4;
            long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
            wanted.add(key);
            if (f.tickets.add(key)) {
                world.addPluginChunkTicket(cx, cz, kit.plugin());
            }
        }
        for (Long key : List.copyOf(f.tickets)) {
            if (!wanted.contains(key)) {
                f.tickets.remove(key);
                world.removePluginChunkTicket((int) (key >> 32), (int) (long) key, kit.plugin());
            }
        }
    }

    private void releaseTickets(Flight f) {
        World world = f.arrival.getWorld();
        if (world == null) {
            return;
        }
        for (Long key : f.tickets) {
            world.removePluginChunkTicket((int) (key >> 32), (int) (long) key, kit.plugin());
        }
        f.tickets.clear();
    }

    private void land(Flight f) {
        flights.remove(f);
        f.sound.stop();
        List<Entity> passengers = List.copyOf(f.ghast.getPassengers());
        f.ghast.eject();
        f.ghast.remove();
        for (Entity e : passengers) {
            var _ = e.teleport(f.arrival);
            if (e instanceof Player p) {
                kit.send(p, "world.airship.arrived");
            }
        }
        releaseTickets(f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (leaving.contains(event.getEntity().getUniqueId())) {
            return;
        }
        for (Flight f : flights) {
            if (f.ghast.equals(event.getDismounted()) && f.segment < f.path.size() - 1) {
                event.setCancelled(true);
                if (event.getEntity() instanceof Player p) {
                    kit.actionBar(p, "world.airship.no_dismount");
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 노틸러스

    /** /노틸러스: 잠수 정류장 근처에서 무료 대여. */
    public void rent(Player player) {
        Optional<Stop> stop = nearStop(player, RegionExt.DockKind.DIVE);
        if (stop.isEmpty()) {
            kit.send(player, "world.nautilus.not_at_stop");
            return;
        }
        Rental old = rentals.remove(player.getUniqueId());
        if (old != null) {
            old.nautilus.remove();
        }
        World world = player.getWorld();
        RegionExt.Point p = stop.get().dock().at();
        Nautilus nautilus =
                world.spawn(
                        new Location(world, p.x(), p.y(), p.z(), p.yaw(), 0),
                        Nautilus.class,
                        n -> {
                            n.setPersistent(false);
                            n.setTamed(true);
                            n.setOwner(player);
                            n.getInventory().setSaddle(ItemStack.of(Material.SADDLE));
                            n.getPersistentDataContainer()
                                    .set(kit.key("ride"), PersistentDataType.BYTE, (byte) 1);
                        });
        nautilus.addPassenger(player);
        rentals.put(player.getUniqueId(), new Rental(nautilus, stop.get().region().id()));
        kit.send(
                player,
                "world.nautilus.rented",
                Placeholder.unparsed(
                        "seconds", String.valueOf(kit.balance().nautilusReturnSeconds())));
    }

    private void rentals() {
        int limit = kit.balance().nautilusReturnSeconds();
        for (Map.Entry<UUID, Rental> e : List.copyOf(rentals.entrySet())) {
            Rental r = e.getValue();
            if (!r.nautilus.isValid()) {
                rentals.remove(e.getKey());
                continue;
            }
            boolean outside =
                    regions.entryAt(WorldKit.loc(r.nautilus))
                            .map(x -> !x.id().equals(r.region))
                            .orElse(true);
            r.idle = r.nautilus.getPassengers().isEmpty() ? r.idle + 1 : 0;
            if (outside || r.idle >= limit) {
                r.nautilus.eject();
                r.nautilus.remove();
                rentals.remove(e.getKey());
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    kit.send(p, "world.nautilus.returned");
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        queue.values().forEach(m -> m.values().forEach(l -> l.remove(uuid)));
        Rental r = rentals.remove(uuid);
        if (r != null) {
            r.nautilus.remove();
        }
        for (Flight f : List.copyOf(flights)) {
            if (f.ghast.getPassengers().contains(event.getPlayer())) {
                // 내리기 막기(onDismount)에 걸리면 저장하지 않는 가스트에 탄 채로 나가 버린다
                leaving.add(uuid);
                try {
                    f.ghast.removePassenger(event.getPlayer());
                } finally {
                    leaving.remove(uuid);
                }
                var _ = event.getPlayer().teleport(f.arrival);
            }
        }
    }

    /** 서버 이동 전: 노틸러스를 회수하고 비행 중이면 거부 (도착 뒤 이동). */
    public HandoffBlocker handoffBlocker() {
        return (player, destination) -> {
            for (Flight f : flights) {
                if (f.ghast.getPassengers().contains(player)) {
                    return new HandoffBlocker.Deny("airship");
                }
            }
            Rental r = rentals.remove(player.getUniqueId());
            if (r != null) {
                r.nautilus.remove();
            }
            return HandoffBlocker.ALLOW;
        };
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity e : event.getEntities()) {
            if (tagged(e)
                    && flights.stream().noneMatch(f -> f.ghast.equals(e))
                    && rentals.values().stream().noneMatch(r -> r.nautilus.equals(e))) {
                e.remove();
            }
        }
    }

    public int flights() {
        return flights.size();
    }
}
