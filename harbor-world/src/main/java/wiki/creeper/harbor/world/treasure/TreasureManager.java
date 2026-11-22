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
import wiki.creeper.harbor.fishing.api.TreasureDropHook;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.TreasureService;
import wiki.creeper.harbor.world.api.event.TreasureClaimedEvent;
import wiki.creeper.harbor.world.content.RegionExt;
import wiki.creeper.harbor.world.content.TreasureTable;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * 보물 지도 (docs 20 8.4 '보물 지도'). Fishing이 잡템으로 보물 지도를 뽑으면 등록한 {@link TreasureDropHook}으로 지도를 만든다 (확률은
 * Fishing 몫). 지도에는 uid·지역·장소·등급과 최대 48블록 어긋난 점을 custom_data에 넣고, 들고 있으면 액션바로 방향과 거리를 보여 준다 (위치 표시
 * 막대 waypoint는 Paper API가 없어 액션바로 대신, Phase 1 검증 항목). 64블록 안이면 정확한 위치, 3블록 안에서 쓰면 3초 파는 연출 뒤 개인
 * 보물상자가 열린다. treasure_claim 삽입(지도 uid 유니크)과 골드 지급(TREASURE, 참조 ID = uid)이 한 트랜잭션이라 복사한 지도는 보상이 없다.
 */
public final class TreasureManager implements TreasureService, TreasureDropHook, Listener {

    private static final HarborId MAP_ITEM = HarborId.parse("item/tool/treasure_map");

    private record MapData(
            UUID uid, RegionId region, String site, String tier, double fx, double fz) {}

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

    // ------------------------------------------------------------------ 만들기

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
                    // 처음 찾은 사람 (감사용, 지도는 거래해도 된다)
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

    // ------------------------------------------------------------------ 안내

    private void hints() {
        WorldBalance b = kit.balance();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Optional<MapData> data =
                    read(player.getInventory().getItemInMainHand())
                            .or(() -> read(player.getInventory().getItemInOffHand()));
            if (data.isEmpty() || digging.contains(player.getUniqueId())) {
                continue;
            }
            Optional<Location> site = siteLocation(data.get());
            Location at = WorldKit.loc(player);
            if (site.isEmpty() || !java.util.Objects.equals(site.get().getWorld(), at.getWorld())) {
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

    /** 바라보는 방향 기준 화살표. */
    static String arrow(float yaw, double dx, double dz) {
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = ((target - yaw) % 360 + 540) % 360 - 180;
        String[] arrows = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
        int index = (int) Math.round(diff / 45.0);
        return arrows[Math.floorMod(index, 8)];
    }

    // ------------------------------------------------------------------ 수령

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

    /** 파는 연출 (Sequence 대신 1초 간격 단계, 움직이면 취소). */
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
        // 골드가 0이면 원장 기록이 없어 atomic()을 쓸 수 없다 (빈 목록은 예외). 수령 기록만 쓴다
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
            // 수령 기록은 커밋됐는데 그사이 나갔거나 서버를 옮기는 중이다: 아이템 보상은 우편으로 보낸다 (골드는 이미 지급됐다).
            // 지도는 인벤토리에 남지만 다시 쓰면 이미 수령한 지도로 처리된다
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
        // 수령 기록은 커밋됐다: 지도가 빠지고 보상을 받은 인벤토리도 곧 저장한다 (골드가 있으면 재화 이벤트로도 저장된다)
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

    /** 접속해 있지 않은 사람의 보물 아이템 보상을 우편으로 (참조 ID가 지도마다 같아서 다시 불러도 한 번만 간다). */
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

    /** 개인 보물상자 (본인에게만 보이는 BlockDisplay, 3초). */
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
