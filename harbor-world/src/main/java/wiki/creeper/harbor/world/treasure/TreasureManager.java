package wiki.creeper.harbor.world.treasure;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.persistence.PersistentDataContainerView;
import java.sql.PreparedStatement;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.MailService;
import wiki.creeper.harbor.core.api.WaypointService;
import wiki.creeper.harbor.fishing.api.TreasureDropHook;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.TreasureService;
import wiki.creeper.harbor.world.api.event.TreasureClaimedEvent;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.content.TreasureTable;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * Treasure maps (docs 20 8.4 'treasure maps'). When Fishing draws a treasure map as junk, the map
 * is made by the registered {@link TreasureDropHook} (the chance is Fishing's job). The map stores
 * uid, region, location, tier and a point offset by up to 48 blocks in custom_data, and while held
 * it shows direction and distance on the action bar and as a locator bar waypoint (Core
 * WaypointService, Phase 1 verification item). Within 64 blocks it shows the exact position, and
 * using it within 3 blocks opens a personal treasure chest after a 3-second digging animation. The
 * treasure_claim insert (map uid unique) and the gold payment (TREASURE, reference ID = uid) are
 * one transaction, so copied maps give no reward.
 */
public final class TreasureManager implements TreasureService, TreasureDropHook, Listener {

    private static final HarborId MAP_ITEM = HarborId.parse("item/tool/treasure_map");

    private record MapData(
            UUID uid, RegionId region, String site, String tier, double fx, double fz) {}

    private static final String WAYPOINT_OWNER = "treasure";

    private final WorldKit kit;
    private final Set<UUID> digging = new HashSet<>();

    public TreasureManager(WorldKit kit) {
        this.kit = kit;
    }

    public void start() {
        var _ = kit.tasks().timer(20, 20, this::hints);
    }

    private TreasureTable table() {
        return kit.api().content().snapshot().single(TreasureTable.TYPE);
    }

    // ------------------------------------------------------------------ creation

    @Override
    public void onTreasureMap(Player player, Location bobber, RegionId region) {
        createMap(player, region)
                .ifPresent(
                        map -> {
                            kit.give(player, map);
                            kit.send(player, "world.treasure.found");
                        });
    }

    @Override
    public Optional<ItemStack> createMap(Player player, RegionId region) {
        return create(player, region, null);
    }

    @Override
    public Optional<ItemStack> createMap(Player player, RegionId region, String tier) {
        return create(player, region, tier);
    }

    private Optional<ItemStack> create(Player player, RegionId regionId, @Nullable String tierId) {
        Optional<WorldIndex.Entry> region = kit.index().get(regionId);
        Optional<ItemDef> def = kit.api().content().snapshot().get(ItemDef.TYPE, MAP_ITEM);
        if (region.isEmpty() || def.isEmpty() || region.get().ext().treasureSites().isEmpty()) {
            return Optional.empty();
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        TreasureTable table = table();
        Optional<TreasureTable.Tier> tier =
                tierId == null ? table.pickTier(regionId, random) : table.tier(tierId, regionId);
        if (tier.isEmpty()) {
            return Optional.empty();
        }
        List<RegionExt.TreasureSite> sites = region.get().ext().treasureSites();
        RegionExt.TreasureSite site = sites.get(random.nextInt(sites.size()));
        double fuzz = kit.balance().treasureFuzz();
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = random.nextDouble() * fuzz;
        double fx = site.at().x() + Math.cos(angle) * dist;
        double fz = site.at().z() + Math.sin(angle) * dist;
        ItemStack map = kit.api().items().create(def.get(), 1);
        UUID uid = UUID.randomUUID();
        map.editPersistentDataContainer(
                pdc -> {
                    pdc.set(kit.key("map_uid"), PersistentDataType.STRING, uid.toString());
                    pdc.set(kit.key("map_region"), PersistentDataType.STRING, regionId.toString());
                    pdc.set(kit.key("map_site"), PersistentDataType.STRING, site.id());
                    pdc.set(kit.key("map_tier"), PersistentDataType.STRING, tier.get().id());
                    pdc.set(kit.key("map_fx"), PersistentDataType.DOUBLE, fx);
                    pdc.set(kit.key("map_fz"), PersistentDataType.DOUBLE, fz);
                    // First finder (for auditing; maps may be traded)
                    pdc.set(
                            kit.key("map_finder"),
                            PersistentDataType.STRING,
                            player.getUniqueId().toString());
                });
        List<Component> lore = new ArrayList<>();
        ItemLore old = map.getData(DataComponentTypes.LORE);
        if (old != null) {
            lore.addAll(old.lines());
        }
        lore.add(
                kit.api()
                        .messages()
                        .get(
                                "world.treasure.lore",
                                Placeholder.parsed("region", region.get().def().name()),
                                Placeholder.unparsed("x", String.valueOf(Math.round(fx))),
                                Placeholder.unparsed("z", String.valueOf(Math.round(fz))))
                        .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        map.setData(DataComponentTypes.LORE, ItemLore.lore(lore));
        return Optional.of(map);
    }

    @Override
    public boolean isTreasureMap(ItemStack stack) {
        return read(stack).isPresent();
    }

    private Optional<MapData> read(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        PersistentDataContainerView pdc = stack.getPersistentDataContainer();
        String uid = pdc.get(kit.key("map_uid"), PersistentDataType.STRING);
        String region = pdc.get(kit.key("map_region"), PersistentDataType.STRING);
        String site = pdc.get(kit.key("map_site"), PersistentDataType.STRING);
        String tier = pdc.get(kit.key("map_tier"), PersistentDataType.STRING);
        Double fx = pdc.get(kit.key("map_fx"), PersistentDataType.DOUBLE);
        Double fz = pdc.get(kit.key("map_fz"), PersistentDataType.DOUBLE);
        if (uid == null
                || region == null
                || site == null
                || tier == null
                || fx == null
                || fz == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    new MapData(UUID.fromString(uid), RegionId.parse(region), site, tier, fx, fz));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<Location> siteLocation(MapData data) {
        return kit.index()
                .get(data.region())
                .flatMap(
                        e ->
                                e.ext().treasureSites().stream()
                                        .filter(s -> s.id().equals(data.site()))
                                        .findFirst()
                                        .flatMap(
                                                s -> {
                                                    World w = kit.bukkitWorld(e.def().world());
                                                    return w == null
                                                            ? Optional.empty()
                                                            : Optional.of(
                                                                    new Location(
                                                                            w,
                                                                            s.at().x() + 0.5,
                                                                            s.at().y(),
                                                                            s.at().z() + 0.5));
                                                }));
    }

    // ------------------------------------------------------------------ guidance

    private void hints() {
        WorldBalance b = kit.balance();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<MapData> data =
                    read(player.getInventory().getItemInMainHand())
                            .or(() -> read(player.getInventory().getItemInOffHand()));
            if (data.isEmpty() || digging.contains(player.getUniqueId())) {
                kit.api().waypoints().clear(player, WAYPOINT_OWNER);
                continue;
            }
            Optional<Location> site = siteLocation(data.get());
            Location at = WorldKit.loc(player);
            if (site.isEmpty() || !java.util.Objects.equals(site.get().getWorld(), at.getWorld())) {
                kit.api().waypoints().clear(player, WAYPOINT_OWNER);
                kit.actionBar(player, "world.treasure.other_world");
                continue;
            }
            double exact = at.distance(site.get());
            boolean precise = exact <= b.treasureExactWithin();
            double tx = precise ? site.get().getX() : data.get().fx();
            double tz = precise ? site.get().getZ() : data.get().fz();
            double dx = tx - at.getX();
            double dz = tz - at.getZ();
            int distance = (int) Math.round(Math.hypot(dx, dz));
            waypoint(player, new Location(at.getWorld(), tx, site.get().getY(), tz));
            kit.actionBar(
                    player,
                    precise ? "world.treasure.near" : "world.treasure.far",
                    Placeholder.unparsed("distance", String.valueOf(distance)),
                    Placeholder.unparsed("arrow", arrow(at.getYaw(), dx, dz)));
            if (precise) {
                player.spawnParticle(
                        Particle.END_ROD,
                        site.get().clone().add(0, 1.5, 0),
                        4,
                        0.2,
                        0.6,
                        0.2,
                        0.01);
            }
        }
    }

    /**
     * Locator bar waypoint toward the point the map shows (the fuzzed point until close, then the
     * exact site), next to the action bar arrow.
     */
    private void waypoint(Player player, Location target) {
        Balance balance = kit.api().content().snapshot().single(Balance.TYPE);
        kit.api()
                .waypoints()
                .set(
                        player,
                        WAYPOINT_OWNER,
                        List.of(
                                new WaypointService.Target(
                                        "map",
                                        target,
                                        balance.string("waypoint.colors.treasure").orElse(null),
                                        balance.string("waypoint.styles.treasure").orElse(null),
                                        balance.number("waypoint.treasure_range", 2048)
                                                * (1 + kit.stat(player, "waypoint_range", 0)))));
    }

    /** Arrow relative to the facing direction. */
    static String arrow(float yaw, double dx, double dz) {
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = ((target - yaw) % 360 + 540) % 360 - 180;
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int index = (int) Math.round(diff / 45.0);
        return arrows[Math.floorMod(index, 8)];
    }

    // ------------------------------------------------------------------ claiming

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                        && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        Optional<MapData> data = read(player.getInventory().getItemInMainHand());
        if (data.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Optional<Location> site = siteLocation(data.get());
        Location at = WorldKit.loc(player);
        double radius = kit.balance().treasureClaimRadius();
        if (site.isEmpty()
                || !java.util.Objects.equals(site.get().getWorld(), at.getWorld())
                || at.distanceSquared(site.get()) > radius * radius + 9) {
            kit.send(player, "world.treasure.not_here");
            return;
        }
        if (!digging.add(player.getUniqueId())) {
            return;
        }
        dig(player, data.get(), site.get(), at, kit.balance().treasureDigTicks(), 0);
    }

    /** Digging animation (1-second steps instead of a Sequence; cancelled on movement). */
    private void dig(
            Player player, MapData data, Location site, Location start, int total, int done) {
        if (!player.isOnline() || WorldKit.loc(player).distanceSquared(start) > 2.25) {
            digging.remove(player.getUniqueId());
            if (player.isOnline()) {
                kit.send(player, "world.treasure.dig_cancel");
            }
            return;
        }
        if (done >= total) {
            claim(player, data, site);
            return;
        }
        player.spawnParticle(
                Particle.BLOCK, site, 20, 0.4, 0.2, 0.4, Material.SAND.createBlockData());
        player.playSound(site, Sound.ITEM_SHOVEL_FLATTEN, 1f, 0.8f);
        var _ = kit.tasks().later(10, () -> dig(player, data, site, start, total, done + 10));
    }

    private void claim(Player player, MapData data, Location site) {
        UUID uuid = player.getUniqueId();
        TreasureTable table = table();
        Optional<TreasureTable.Tier> tier = table.tier(data.tier(), data.region());
        if (tier.isEmpty()) {
            digging.remove(uuid);
            kit.send(player, "world.treasure.failed");
            return;
        }
        TreasureTable.Roll roll =
                table.roll(tier.get(), data.region(), ThreadLocalRandom.current());
        int season = kit.season();
        String ref = data.uid().toString();
        SqlConsumer insert =
                c -> {
                    try (PreparedStatement ps =
                            c.prepareStatement(
                                    "INSERT INTO treasure_claim (map_uid, uuid, season, region_id, site_id, tier, gold) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        ps.setObject(1, data.uid());
                        ps.setObject(2, uuid);
                        ps.setInt(3, season);
                        ps.setString(4, data.region().toString());
                        ps.setString(5, data.site());
                        ps.setString(6, data.tier());
                        ps.setLong(7, roll.gold());
                        ps.executeUpdate();
                    } catch (SQLIntegrityConstraintViolationException e) {
                        throw new wiki.creeper.harbor.common.ledger.LedgerException(
                                LedgerError.DUPLICATE, "이미 수령한 지도");
                    }
                };
        // With 0 gold there is no ledger entry, so atomic() cannot be used (an empty list throws).
        // Write only the claim record
        CompletableFuture<Result<List<Long>, LedgerError>> work =
                roll.gold() > 0
                        ? kit.api()
                                .currency()
                                .atomic(
                                        List.of(
                                                new LedgerEntry(
                                                        uuid,
                                                        Currency.GOLD,
                                                        roll.gold(),
                                                        LedgerReason.TREASURE,
                                                        ref)),
                                        insert)
                        : kit.api()
                                .database()
                                .asyncVoid(insert)
                                .handleAsync(
                                        (v, e) -> {
                                            if (e == null) {
                                                return Result.<List<Long>, LedgerError>ok(
                                                        List.of());
                                            }
                                            Throwable cause =
                                                    e.getCause() == null ? e : e.getCause();
                                            return Result.<List<Long>, LedgerError>err(
                                                    cause
                                                                    instanceof
                                                                    wiki.creeper.harbor.common
                                                                                    .ledger
                                                                                    .LedgerException
                                                                            le
                                                            ? le.error()
                                                            : LedgerError.DATABASE);
                                        },
                                        kit.tasks().main());
        var _ = work.thenAccept(result -> finish(player, data, site, roll, result));
    }

    private void finish(
            Player player,
            MapData data,
            Location site,
            TreasureTable.Roll roll,
            Result<List<Long>, LedgerError> result) {
        digging.remove(player.getUniqueId());
        if (!player.isOnline() || kit.api().players().frozen(player.getUniqueId())) {
            // The claim record is committed, but the player left or is moving servers in the
            // meantime: item
            // rewards are sent by mail (gold was already paid). The map stays in the inventory, but
            // using
            // it again is treated as an already claimed map
            if (result.isOk()) {
                mailRewards(player.getUniqueId(), data, roll);
            }
            return;
        }
        boolean duplicate =
                result instanceof Result.Err<List<Long>, LedgerError>(LedgerError error)
                        && error == LedgerError.DUPLICATE;
        if (!result.isOk() && !duplicate) {
            kit.send(player, "world.treasure.failed");
            return;
        }
        removeMap(player, data.uid());
        if (duplicate) {
            kit.send(player, "world.treasure.already");
            return;
        }
        for (Map.Entry<ItemId, Integer> e : roll.items().entrySet()) {
            kit.createItem(e.getKey().id(), e.getValue())
                    .ifPresent(stack -> kit.give(player, stack));
        }
        // The claim record is committed: save the inventory, now without the map and with the
        // rewards,
        // soon too (with gold it is also saved by the currency event)
        kit.api().players().saveSoon(player.getUniqueId());
        showChest(player, site);
        kit.send(
                player,
                "world.treasure.claimed",
                Placeholder.unparsed("reward", String.valueOf(roll.gold())));
        Bukkit.getPluginManager()
                .callEvent(
                        new TreasureClaimedEvent(
                                player,
                                data.region(),
                                data.site(),
                                data.tier(),
                                roll.gold(),
                                roll.hint()));
    }

    /**
     * Mails treasure item rewards to someone who is not online (the reference ID is the same per
     * map, so calling it again sends only once).
     */
    private void mailRewards(UUID uuid, MapData data, TreasureTable.Roll roll) {
        Optional<MailService> mail = kit.api().mail();
        int i = 0;
        for (Map.Entry<ItemId, Integer> e : roll.items().entrySet()) {
            Optional<ItemStack> stack = kit.createItem(e.getKey().id(), e.getValue());
            if (stack.isEmpty()) {
                continue;
            }
            String ref = "treasure:" + data.uid() + ":" + i++;
            if (mail.isEmpty()) {
                kit.log().error("우편함이 없어 보물 보상을 줄 수 없습니다 (관리자 복구 필요): {} {}", uuid, stack.get());
                continue;
            }
            var _ =
                    mail.get()
                            .sendItem(uuid, stack.get(), "treasure", ref)
                            .exceptionally(
                                    ex -> {
                                        kit.log()
                                                .error(
                                                        "보물 보상 우편 실패 (관리자 복구 필요): {} {}",
                                                        uuid,
                                                        ref,
                                                        ex);
                                        return null;
                                    });
        }
    }

    private void removeMap(Player player, UUID uid) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            Optional<MapData> d = read(contents[i]);
            if (d.isPresent() && d.get().uid().equals(uid)) {
                ItemStack stack = contents[i];
                if (stack != null) {
                    stack.setAmount(stack.getAmount() - 1);
                    player.getInventory().setItem(i, stack.getAmount() <= 0 ? null : stack);
                }
                return;
            }
        }
    }

    /** Personal treasure chest (BlockDisplay visible only to that player, 3 seconds). */
    private void showChest(Player player, Location site) {
        World world = site.getWorld();
        if (world == null) {
            return;
        }
        BlockDisplay chest =
                world.spawn(
                        site.clone().add(-0.5, 0, -0.5),
                        BlockDisplay.class,
                        d -> {
                            d.setBlock(Material.CHEST.createBlockData());
                            d.setPersistent(false);
                            d.setVisibleByDefault(false);
                        });
        player.showEntity(kit.plugin(), chest);
        player.playSound(site, Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        player.spawnParticle(
                Particle.HAPPY_VILLAGER, site.clone().add(0, 1, 0), 20, 0.4, 0.4, 0.4, 0);
        var _ = kit.tasks().later(60, chest::remove);
    }
}
