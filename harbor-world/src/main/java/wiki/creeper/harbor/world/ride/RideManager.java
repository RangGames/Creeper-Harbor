package wiki.creeper.harbor.world.ride;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
import wiki.creeper.harbor.world.api.RideService;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * Rides (docs 20 8.4 'rides').
 *
 * <ul>
 *   <li>Harbor airship (happy ghast): routes between stations of unlocked regions. A route opens
 *       only after visiting that region once by ship (region_visit). Departs every 3 minutes (on
 *       the clock), 4 riders per ghast, another ghast if it overflows. AI is off and the server
 *       moves it along the path (ascend → cruise → descend), blocking dismount in flight. Chunk
 *       tickets 3 chunks ahead on the path. If the server shuts down, riders are moved to the
 *       destination. The control scheme is a Phase 1 verification item
 *   <li>Deep-sea ride (nautilus): free rental at the Ashen Trench dive station. Recalled 30 seconds
 *       after dismounting or when leaving the trench. With nautilus armor, oxygen drain ×0.5
 *       (EnvironmentManager)
 * </ul>
 *
 * No entity is saved (setPersistent false); leftovers are removed by their PDC tag. Main thread
 * only.
 */
public final class RideManager implements RideService, Listener {

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
        final RegionId dest;
        int segment;
        final Set<Long> tickets = new java.util.HashSet<>();

        /** Looping flight sound (travel.airship, '15. sound' 4.4). */
        HarborSounds.Loop sound = HarborSounds.Loop.NONE;

        Flight(HappyGhast ghast, List<Location> path, Location arrival, RegionId dest) {
            this.ghast = ghast;
            this.path = path;
            this.arrival = arrival;
            this.dest = dest;
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

    /** (departure station key → destination region → waiting passengers). */
    private final Map<String, Map<RegionId, List<UUID>>> queue = new LinkedHashMap<>();

    private final List<Flight> flights = new ArrayList<>();
    private final Map<UUID, Rental> rentals = new HashMap<>();

    /**
     * Players allowed to dismount even in flight because they are quitting (only during onQuit).
     */
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

    // ------------------------------------------------------------------ airship

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

    /**
     * Routes this player can take from the current station (same world, visited region, entry
     * conditions met apart from the vehicle condition).
     */
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

    /** /비행선 [region]: picks a route near a station and waits for the next departure. */
    public void boardOrChoose(Player player, @Nullable RegionId target) {
        if (target != null) {
            var _ = board(player, target);
            return;
        }
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
    }

    @Override
    public BoardResult board(Player player, RegionId target) {
        Optional<Stop> from = nearStop(player, RegionExt.DockKind.AIRSHIP);
        if (from.isEmpty()) {
            kit.send(player, "world.airship.not_at_stop");
            return BoardResult.NOT_AT_STOP;
        }
        List<WorldIndex.Entry> options = destinations(player, from.get());
        if (options.isEmpty()) {
            kit.send(player, "world.airship.no_route");
            return BoardResult.NO_ROUTE;
        }
        Optional<WorldIndex.Entry> dest =
                options.stream().filter(e -> e.id().equals(target)).findFirst();
        if (dest.isEmpty()) {
            kit.send(player, "world.airship.locked");
            return BoardResult.LOCKED;
        }
        enqueue(player, from.get(), dest.get());
        return BoardResult.QUEUED;
    }

    private Optional<Stop> airshipStop(RegionId region) {
        return stops(RegionExt.DockKind.AIRSHIP).stream()
                .filter(s -> s.region().id().equals(region))
                .findFirst();
    }

    @Override
    public boolean launch(Collection<Player> riders, RegionId fromId, RegionId toId) {
        Optional<Stop> from = airshipStop(fromId);
        Optional<Stop> to = airshipStop(toId);
        if (from.isEmpty()
                || to.isEmpty()
                || fromId.equals(toId)
                || !from.get().region().def().world().equals(to.get().region().def().world())) {
            return false;
        }
        World world = kit.bukkitWorld(from.get().region().def().world());
        if (world == null) {
            return false;
        }
        RegionExt.Point o = from.get().dock().at();
        Location origin = new Location(world, o.x(), o.y(), o.z(), o.yaw(), 0);
        List<Player> boarding = new ArrayList<>();
        for (Player p : riders) {
            if (!p.isOnline() || !p.getWorld().equals(world) || riding(p)) {
                continue;
            }
            UUID uuid = p.getUniqueId();
            queue.values().forEach(m -> m.values().forEach(l -> l.remove(uuid)));
            p.leaveVehicle();
            if (p.teleport(origin)) {
                boarding.add(p);
            }
        }
        if (boarding.isEmpty()) {
            return false;
        }
        WorldBalance b = kit.balance();
        int capacity = Math.max(1, b.airshipCapacity());
        for (int i = 0; i < boarding.size(); i += capacity) {
            launch(
                    origin,
                    to.get(),
                    boarding.subList(i, Math.min(boarding.size(), i + capacity)),
                    b);
        }
        return true;
    }

    private Optional<Flight> flightOf(Player player) {
        return flights.stream().filter(f -> f.ghast.getPassengers().contains(player)).findFirst();
    }

    @Override
    public boolean riding(Player player) {
        return flightOf(player).isPresent();
    }

    @Override
    public boolean queued(Player player) {
        UUID uuid = player.getUniqueId();
        return queue.values().stream()
                .anyMatch(m -> m.values().stream().anyMatch(l -> l.contains(uuid)));
    }

    @Override
    public Optional<RegionId> destination(Player player) {
        return flightOf(player).map(f -> f.dest);
    }

    @Override
    public boolean stop(Player player) {
        UUID uuid = player.getUniqueId();
        boolean wasQueued = queued(player);
        queue.values().forEach(m -> m.values().forEach(l -> l.remove(uuid)));
        Optional<Flight> flight = flightOf(player);
        if (flight.isEmpty()) {
            return wasQueued;
        }
        Flight f = flight.get();
        leaving.add(uuid);
        try {
            f.ghast.removePassenger(player);
        } finally {
            leaving.remove(uuid);
        }
        var _ = player.teleport(f.arrival);
        kit.send(player, "world.airship.arrived");
        if (f.ghast.getPassengers().isEmpty()) {
            land(f);
        }
        return true;
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

    /** Every second: boards waiting passengers at departure time, recalls nautiluses. */
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
        Flight flight = new Flight(ghast, path, arrival, dest.region().id());
        flight.sound =
                HarborSounds.loopFrom(
                        kit.tasks(),
                        ghast,
                        AIRSHIP_SOUND,
                        HarborSounds.Options.DEFAULT.withSource(Sound.Source.NEUTRAL));
        flights.add(flight);
    }

    /** Every tick: moves along the path (teleport, passengers come along). */
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
            // If the teleport is denied (world border, cancelled event, etc.) the airship stops in
            // place and
            // riders can neither dismount nor move: let them off right there
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

    // ------------------------------------------------------------------ nautilus

    /** /노틸러스: free rental near a dive station. */
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
                // If the dismount block (onDismount) catches it, the player quits while still
                // riding the
                // non-persistent ghast
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

    /**
     * Before a server move: recalls the nautilus, and denies while in flight (move after arrival).
     */
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
