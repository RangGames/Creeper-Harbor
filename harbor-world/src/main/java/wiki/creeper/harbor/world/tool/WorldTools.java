package wiki.creeper.harbor.world.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.WaypointService;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ActiveSeaEvent;
import wiki.creeper.harbor.world.api.SeaEventService;
import wiki.creeper.harbor.world.region.RegionManager;
import wiki.creeper.harbor.world.region.ServerRouter;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * Navigation and exploration tools ('3. item list' 5 and 6, proposed behaviour). Right-click with
 * the item in the main hand. Numbers are balance.yml world.tools. Main thread only.
 *
 * <ul>
 *   <li>Return conch (consumed): back to the harbor safe point after a shared cooldown. The local
 *       teleport uses the CONSUMABLE_EFFECT cause, so Boss blocks it inside an arena just like an
 *       ender pearl; a move to another server goes through the handoff blockers.
 *   <li>Navigation compass: a locator bar waypoint to the nearest region in this world that the
 *       player has not visited this season.
 *   <li>Telescope: sea events in progress (remaining time), announced events (time to start) and
 *       the running world boss (harbor:worldboss broadcast, read without depending on Boss).
 * </ul>
 */
public final class WorldTools implements Listener {

    static final HarborId CONCH = HarborId.parse("item/tool/return_conch");
    static final HarborId COMPASS = HarborId.parse("item/tool/nav_compass");
    static final HarborId TELESCOPE = HarborId.parse("item/tool/telescope");
    private static final String COMPASS_OWNER = "nav_compass";

    private final WorldKit kit;
    private final RegionManager regions;
    private final SeaEventService events;
    private final WorldBossWatch boss;

    /** Cooldown end (epoch millis) per player and tool. */
    private final Map<UUID, Map<HarborId, Long>> cooldowns = new HashMap<>();

    /** Compass waypoint generation per player (a newer use replaces the clear timer). */
    private final Map<UUID, Integer> compassGen = new HashMap<>();

    public WorldTools(
            WorldKit kit, RegionManager regions, SeaEventService events, WorldBossWatch boss) {
        this.kit = kit;
        this.regions = regions;
        this.events = events;
        this.boss = boss;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Optional<ItemId> id = kit.itemId(event.getItem());
        if (id.isEmpty()) {
            return;
        }
        HarborId item = id.get().id();
        Player player = event.getPlayer();
        if (item.equals(CONCH)) {
            event.setCancelled(true);
            conch(player);
        } else if (item.equals(COMPASS)) {
            event.setCancelled(true);
            compass(player);
        } else if (item.equals(TELESCOPE)) {
            // The vanilla spyglass zoom still happens (the event is not cancelled)
            telescope(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        compassGen.remove(uuid);
        Map<HarborId, Long> map = cooldowns.get(uuid);
        if (map != null) {
            long now = System.currentTimeMillis();
            map.values().removeIf(t -> t <= now);
            if (map.isEmpty()) {
                cooldowns.remove(uuid);
            }
        }
    }

    // ------------------------------------------------------------------ cooldowns

    /** Remaining cooldown seconds (0 = ready). */
    private long remaining(Player player, HarborId tool) {
        Map<HarborId, Long> map = cooldowns.get(player.getUniqueId());
        Long until = map == null ? null : map.get(tool);
        if (until == null) {
            return 0;
        }
        long left = until - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    private void startCooldown(Player player, HarborId tool, int seconds, ItemStack shown) {
        if (seconds <= 0) {
            return;
        }
        cooldowns
                .computeIfAbsent(player.getUniqueId(), u -> new HashMap<>())
                .put(tool, System.currentTimeMillis() + seconds * 1000L);
        player.setCooldown(shown, seconds * 20);
    }

    // ------------------------------------------------------------------ return conch

    private void conch(Player player) {
        long left = remaining(player, CONCH);
        if (left > 0) {
            kit.send(
                    player,
                    "world.tools.conch.cooldown",
                    Placeholder.unparsed("seconds", String.valueOf(left)));
            return;
        }
        Optional<WorldIndex.Entry> entry;
        try {
            entry = kit.index().get(RegionId.parse(kit.balance().conchRegion()));
        } catch (IllegalArgumentException e) {
            entry = Optional.empty();
        }
        if (entry.isEmpty()) {
            kit.send(player, "world.tools.conch.failed");
            return;
        }
        ItemStack shown = player.getInventory().getItemInMainHand().clone();
        ServerRouter.Destination to = RegionManager.spawnOf(entry.get());
        World world = kit.bukkitWorld(to.world());
        if (world == null) {
            // Harbor on another server: the move goes through the handoff blockers (boss fights)
            if (regions.travel(player, entry.get().id(), "conch")) {
                consume(player, shown);
            }
            return;
        }
        player.leaveVehicle();
        kit.send(player, "world.tools.conch.used");
        var _ =
                player.teleportAsync(
                                new Location(world, to.x(), to.y(), to.z(), to.yaw(), 0),
                                PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT)
                        .thenAcceptAsync(
                                ok -> {
                                    if (Boolean.TRUE.equals(ok) && player.isOnline()) {
                                        consume(player, shown);
                                    }
                                },
                                kit.tasks().main());
    }

    /** Removes one conch (the held one first) and starts the cooldown. */
    private void consume(Player player, ItemStack shown) {
        if (!takeOne(player, CONCH)) {
            return;
        }
        kit.api().players().saveSoon(player.getUniqueId());
        startCooldown(player, CONCH, kit.balance().conchCooldownSeconds(), shown);
    }

    private boolean takeOne(Player player, HarborId item) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (matches(hand, item)) {
            hand.setAmount(hand.getAmount() - 1);
            return true;
        }
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && matches(stack, item)) {
                stack.setAmount(stack.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    private boolean matches(@Nullable ItemStack stack, HarborId item) {
        return kit.itemId(stack).filter(i -> i.id().equals(item)).isPresent();
    }

    // ------------------------------------------------------------------ navigation compass

    private void compass(Player player) {
        if (remaining(player, COMPASS) > 0) {
            return;
        }
        ItemStack shown = player.getInventory().getItemInMainHand().clone();
        startCooldown(player, COMPASS, kit.balance().compassCooldownSeconds(), shown);
        Location at = WorldKit.loc(player);
        String here = kit.worldId(at);
        java.util.Set<RegionId> visited = regions.visited(player.getUniqueId());
        WorldIndex.Entry best = null;
        Location bestLoc = null;
        double bestDist = Double.MAX_VALUE;
        for (WorldIndex.Entry e : kit.index().regions()) {
            if (visited.contains(e.id()) || !e.def().world().equals(here)) {
                continue;
            }
            ServerRouter.Destination d = RegionManager.spawnOf(e);
            Location loc = new Location(at.getWorld(), d.x(), at.getY(), d.z());
            double dist = loc.distanceSquared(at);
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
                bestLoc = loc;
            }
        }
        if (best == null || bestLoc == null) {
            kit.api().waypoints().clear(player, COMPASS_OWNER);
            kit.send(player, "world.tools.compass.none");
            return;
        }
        double range = Math.sqrt(bestDist) + 256;
        kit.api()
                .waypoints()
                .set(
                        player,
                        COMPASS_OWNER,
                        List.of(
                                new WaypointService.Target(
                                        best.id().toString(),
                                        bestLoc,
                                        kit.balance().compassColor(),
                                        null,
                                        range)));
        kit.send(
                player,
                "world.tools.compass.target",
                Placeholder.parsed("region", best.info().name()),
                Placeholder.unparsed("distance", String.valueOf(Math.round(Math.sqrt(bestDist)))));
        UUID uuid = player.getUniqueId();
        int gen = compassGen.merge(uuid, 1, Integer::sum);
        var _ =
                kit.tasks()
                        .later(
                                kit.balance().compassSeconds() * 20L,
                                () -> {
                                    if (compassGen.getOrDefault(uuid, 0) == gen
                                            && player.isOnline()) {
                                        kit.api().waypoints().clear(player, COMPASS_OWNER);
                                    }
                                });
    }

    // ------------------------------------------------------------------ telescope

    private void telescope(Player player) {
        if (remaining(player, TELESCOPE) > 0) {
            return;
        }
        ItemStack shown = player.getInventory().getItemInMainHand().clone();
        startCooldown(player, TELESCOPE, kit.balance().telescopeCooldownSeconds(), shown);
        kit.send(player, "world.tools.telescope.header");
        long now = System.currentTimeMillis();
        boolean any = false;
        for (ActiveSeaEvent e : events.active()) {
            any = true;
            kit.send(
                    player,
                    "world.tools.telescope.active",
                    Placeholder.parsed("name", e.name()),
                    Placeholder.component("region", regionName(e.region())),
                    Placeholder.component("time", time(e.endsAt().toEpochMilli() - now)));
        }
        for (ActiveSeaEvent e : events.upcoming()) {
            any = true;
            kit.send(
                    player,
                    "world.tools.telescope.upcoming",
                    Placeholder.parsed("name", e.name()),
                    Placeholder.component("region", regionName(e.region())),
                    Placeholder.component("time", time(e.startsAt().toEpochMilli() - now)));
        }
        Optional<WorldBossWatch.Progress> worldBoss =
                boss.latest(kit.balance().telescopeBossStaleSeconds() * 1000L);
        if (worldBoss.isPresent()) {
            any = true;
            WorldBossWatch.Progress p = worldBoss.get();
            kit.send(
                    player,
                    "world.tools.telescope.boss",
                    Placeholder.parsed("name", p.name()),
                    Placeholder.unparsed("percent", String.format("%.1f", p.percent())),
                    Placeholder.unparsed("participants", String.valueOf(p.participants())));
        }
        if (!any) {
            kit.send(player, "world.tools.telescope.calm");
        }
    }

    private Component regionName(@Nullable RegionId region) {
        if (region == null) {
            return kit.api().messages().get("world.tools.telescope.all_waters");
        }
        String name = kit.index().get(region).map(e -> e.info().name()).orElse(region.id().name());
        return MiniMessage.miniMessage().deserialize(name);
    }

    private Component time(long millis) {
        long total = Math.max(0, millis / 1000);
        return kit.api()
                .messages()
                .get(
                        "world.tools.time",
                        Placeholder.unparsed("minutes", String.valueOf(total / 60)),
                        Placeholder.unparsed("seconds", String.valueOf(total % 60)));
    }
}
