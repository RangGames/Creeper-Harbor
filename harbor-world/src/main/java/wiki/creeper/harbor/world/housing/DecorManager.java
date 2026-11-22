package wiki.creeper.harbor.world.housing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;
import wiki.creeper.harbor.common.content.type.FurnitureDef;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.event.FurnitureRemoveEvent;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.world.Icons;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.PlotPermission;
import wiki.creeper.harbor.world.api.event.DisplayAnchorChangedEvent;
import wiki.creeper.harbor.world.bridge.Payments;
import wiki.creeper.harbor.world.content.DecorDef;

/**
 * 하우징 장식 (docs 20 8.4 '장식 상점' · '개인 어항'). 장식 상점(housing_decor.yml)에서 사면 원장(HOUSING_DECOR)과 보유
 * 개수(housing_decor_owned)를 한 트랜잭션으로 바꾼다 (Economy가 있으면 ShopService.charge). 산 장식은 인벤토리가 아니라 개수로 쌓이고,
 * 플롯 안 팔레트에서 꺼내 설치한다. 부수면 개수로 돌아오고, 하우징 월드를 나가면 가방에 든 장식 아이템은 보관함(개수)으로 돌아간다.
 *
 * <p>설치한 블록 장식은 청크 PDC에 위치 → 장식 ID로 남긴다. 수조(tank) 장식을 놓으면 개인 어항 자리(DisplayAnchor, housing_anchor)를
 * 만들고 DisplayAnchorChangedEvent로 Collection에 알린다. 가구(furniture)는 ItemDisplay + Interaction(웅크리고
 * 우클릭하면 회수).
 */
public final class DecorManager implements Listener {

    private final WorldKit kit;
    private final HousingManager housing;
    private final Payments payments;
    private final Function<UUID, CompletableFuture<Integer>> bestTier;
    private final Map<UUID, Map<HarborId, Integer>> owned = new HashMap<>();

    public DecorManager(
            WorldKit kit,
            HousingManager housing,
            Payments payments,
            Function<UUID, CompletableFuture<Integer>> bestTier) {
        this.kit = kit;
        this.housing = housing;
        this.payments = payments;
        this.bestTier = bestTier;
    }

    public void start() {
        Bukkit.getOnlinePlayers().forEach(this::load);
    }

    private NamespacedKey tag() {
        return kit.key("decor");
    }

    private Optional<DecorDef> def(HarborId id) {
        return kit.api().content().snapshot().get(DecorDef.TYPE, id);
    }

    private Optional<DecorDef> def(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        String raw = stack.getPersistentDataContainer().get(tag(), PersistentDataType.STRING);
        return raw == null ? Optional.empty() : HarborId.tryParse(raw).flatMap(this::def);
    }

    public int count(UUID player, HarborId decor) {
        return owned.getOrDefault(player, Map.of()).getOrDefault(decor, 0);
    }

    private void load(Player player) {
        UUID uuid = player.getUniqueId();
        var _ =
                kit.api()
                        .database()
                        .async(c -> HousingRepository.decor(c, uuid))
                        .thenAcceptAsync(
                                map -> owned.put(uuid, new HashMap<>(map)), kit.tasks().main());
    }

    private void addCount(UUID uuid, HarborId decor, int amount) {
        owned.computeIfAbsent(uuid, u -> new HashMap<>()).merge(decor, amount, Integer::sum);
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(c -> HousingRepository.addDecor(c, uuid, decor, amount));
    }

    /** 월드 저장을 기다리는 회수분 (주인 → 장식 → 개수). 메인 스레드 전용. */
    private final Map<UUID, Map<HarborId, Integer>> unsavedCredits = new HashMap<>();

    private final Set<UUID> worldSavePending = new HashSet<>();

    /**
     * 월드에서 치운 장식(블록·가구 엔티티)을 보관함으로 돌린다. 치운 것은 청크와 함께 저장되므로, 개수를 바로 DB에 쓰면 청크가 저장되기 전 서버가 죽었을 때 장식이
     * 월드에도 되살아나고 보관함에도 남는다. 메모리 개수는 바로 늘리고, 다음 틱에 그 월드를 저장한 뒤 DB에 쓴다 (여러 번 치워도 저장은 틱마다 한 번, 추가 제안).
     * 하우징 월드는 플롯 근처 청크만 로드돼 있어 저장 비용이 작다.
     */
    private void creditAfterWorldSave(World world, UUID owner, HarborId decor, int amount) {
        owned.computeIfAbsent(owner, u -> new HashMap<>()).merge(decor, amount, Integer::sum);
        unsavedCredits
                .computeIfAbsent(owner, u -> new HashMap<>())
                .merge(decor, amount, Integer::sum);
        UUID worldId = world.getUID();
        if (!worldSavePending.add(worldId)) {
            return;
        }
        var _ =
                kit.tasks()
                        .later(
                                1,
                                () -> {
                                    worldSavePending.remove(worldId);
                                    World w = Bukkit.getWorld(worldId);
                                    if (w != null) {
                                        w.save();
                                    }
                                    flushCredits(false);
                                });
    }

    /** 월드 저장 뒤 (또는 종료 때: 월드는 플러그인이 꺼진 뒤 저장된다) 기다리던 회수분을 DB에 쓴다. */
    public void flushCredits(boolean blocking) {
        if (unsavedCredits.isEmpty()) {
            return;
        }
        Map<UUID, Map<HarborId, Integer>> batch = new HashMap<>(unsavedCredits);
        unsavedCredits.clear();
        SqlConsumer write =
                c -> {
                    for (var owner : batch.entrySet()) {
                        for (var e : owner.getValue().entrySet()) {
                            HousingRepository.addDecor(c, owner.getKey(), e.getKey(), e.getValue());
                        }
                    }
                };
        if (!blocking) {
            var _ = kit.api().database().asyncVoid(write);
            return;
        }
        // 종료 중: DB 연결이 곧 닫히므로 기다려서 쓴다
        try {
            kit.api().database().txVoid(write);
        } catch (java.sql.SQLException e) {
            kit.log().error("장식 회수분 저장 실패 (종료 중): {}", batch, e);
        }
    }

    // ------------------------------------------------------------------ 상점

    /** 장식 상점 (/부두 상점). */
    public void openShop(Player player) {
        MessageService msg = kit.api().messages();
        List<DecorDef> defs =
                new ArrayList<>(kit.api().content().snapshot().all(DecorDef.TYPE).values());
        defs.sort(Comparator.comparing(DecorDef::category).thenComparingLong(DecorDef::price));
        var _ =
                bestTier.apply(player.getUniqueId())
                        .thenAccept(
                                tier -> {
                                    PagedMenu<DecorDef> menu =
                                            new PagedMenu<>(
                                                    msg.get("world.decor.shop_title"),
                                                    6,
                                                    defs,
                                                    d ->
                                                            new PagedMenu.Entry(
                                                                    icon(player, d, tier, true),
                                                                    e ->
                                                                            confirmBuy(
                                                                                    player, d,
                                                                                    tier)),
                                                    Icons.of(
                                                            Material.ARROW,
                                                            msg.get("world.decor.previous"),
                                                            List.of()),
                                                    Icons.of(
                                                            Material.ARROW,
                                                            msg.get("world.decor.next"),
                                                            List.of()));
                                    menu.show(player, 0);
                                });
    }

    private ItemStack icon(Player player, DecorDef d, int tier, boolean shop) {
        MessageService msg = kit.api().messages();
        List<Component> lore = new ArrayList<>();
        if (shop) {
            lore.add(
                    msg.get(
                            "world.decor.lore.price",
                            Placeholder.unparsed("price", String.valueOf(d.price())),
                            Placeholder.unparsed("amount", String.valueOf(d.amount()))));
            if (d.reputationMin() > tier) {
                lore.add(
                        msg.get(
                                "world.decor.lore.locked",
                                Placeholder.unparsed("tier", String.valueOf(d.reputationMin()))));
            }
        }
        lore.add(
                msg.get(
                        "world.decor.lore.owned",
                        Placeholder.unparsed(
                                "count", String.valueOf(count(player.getUniqueId(), d.id())))));
        if (!shop) {
            lore.add(msg.get("world.decor.lore.take"));
        }
        return Icons.of(base(d), Component.text(d.name()), lore, iconModel(d));
    }

    /** 아이콘 모델: 직접 적은 model, 없으면 Core 가구·커스텀 블록의 모델. */
    private @Nullable String iconModel(DecorDef d) {
        if (d.model() != null) {
            return d.model();
        }
        var snapshot = kit.api().content().snapshot();
        HarborId furniture = d.furniture();
        if (furniture != null) {
            return snapshot.get(FurnitureDef.TYPE, furniture).map(FurnitureDef::model).orElse(null);
        }
        HarborId block = d.block();
        if (block != null) {
            return snapshot.get(CustomBlockDef.TYPE, block)
                    .map(CustomBlockDef::itemModel)
                    .orElse(null);
        }
        return null;
    }

    private ItemStack base(DecorDef d) {
        if (d.block() != null) {
            return ItemStack.of(Material.NOTE_BLOCK);
        }
        String raw = d.material();
        Material m = raw == null ? null : Material.matchMaterial(raw);
        return ItemStack.of(m != null && m.isItem() ? m : Material.PAPER);
    }

    private void confirmBuy(Player player, DecorDef d, int tier) {
        if (d.reputationMin() > tier) {
            kit.send(
                    player,
                    "world.decor.locked",
                    Placeholder.unparsed("tier", String.valueOf(d.reputationMin())));
            return;
        }
        player.closeInventory();
        MessageService msg = kit.api().messages();
        kit.api()
                .dialogs()
                .confirm(
                        player,
                        msg.get("world.decor.buy_title"),
                        List.of(
                                msg.get(
                                        "world.decor.buy_body",
                                        Placeholder.unparsed("name", d.name()),
                                        Placeholder.unparsed("amount", String.valueOf(d.amount())),
                                        Placeholder.unparsed("price", String.valueOf(d.price())))),
                        msg.get("world.decor.yes"),
                        msg.get("world.decor.no"),
                        ok -> {
                            if (ok) {
                                buy(player, d);
                            }
                        });
    }

    private void buy(Player player, DecorDef d) {
        UUID uuid = player.getUniqueId();
        HarborId id = d.id();
        int amount = d.amount();
        var _ =
                payments.charge(
                                player,
                                d.price(),
                                LedgerReason.HOUSING_DECOR,
                                "decor:" + UUID.randomUUID(),
                                c -> HousingRepository.addDecor(c, uuid, id, amount))
                        .thenAccept(
                                result -> {
                                    if (!result.isOk()) {
                                        kit.send(player, Payments.failKey(result));
                                        return;
                                    }
                                    owned.computeIfAbsent(uuid, u -> new HashMap<>())
                                            .merge(id, amount, Integer::sum);
                                    kit.send(
                                            player,
                                            "world.decor.bought",
                                            Placeholder.unparsed("name", d.name()),
                                            Placeholder.unparsed("amount", String.valueOf(amount)),
                                            Placeholder.unparsed(
                                                    "price", String.valueOf(d.price())));
                                });
    }

    // ------------------------------------------------------------------ 팔레트

    /** 장식 팔레트 (/부두 장식): 건축할 수 있는 플롯 안에서만. 클릭 1개, 쉬프트 클릭 16개. */
    public void openPalette(Player player) {
        Optional<HousingManager.PlotState> plot = housing.stateAt(WorldKit.loc(player));
        if (plot.isEmpty() || !housing.can(player, plot.get().info, PlotPermission.BUILD)) {
            kit.send(player, "world.decor.not_in_plot");
            return;
        }
        MessageService msg = kit.api().messages();
        UUID uuid = player.getUniqueId();
        List<DecorDef> mine = new ArrayList<>();
        for (Map.Entry<HarborId, Integer> e : owned.getOrDefault(uuid, Map.of()).entrySet()) {
            if (e.getValue() > 0) {
                def(e.getKey()).ifPresent(mine::add);
            }
        }
        mine.sort(Comparator.comparing(DecorDef::category).thenComparing(DecorDef::name));
        if (mine.isEmpty()) {
            kit.send(player, "world.decor.empty");
            return;
        }
        PagedMenu<DecorDef> menu =
                new PagedMenu<>(
                        msg.get("world.decor.palette_title"),
                        6,
                        mine,
                        d ->
                                new PagedMenu.Entry(
                                        icon(player, d, 0, false),
                                        e ->
                                                take(
                                                        player,
                                                        d,
                                                        e.getClick() == ClickType.SHIFT_LEFT
                                                                ? 16
                                                                : 1)),
                        Icons.of(Material.ARROW, msg.get("world.decor.previous"), List.of()),
                        Icons.of(Material.ARROW, msg.get("world.decor.next"), List.of()));
        menu.show(player, 0);
    }

    private void take(Player player, DecorDef d, int wanted) {
        UUID uuid = player.getUniqueId();
        int amount = Math.min(wanted, count(uuid, d.id()));
        if (d.kind() != DecorDef.Kind.BLOCK) {
            amount = Math.min(amount, 1);
        }
        if (amount <= 0) {
            return;
        }
        int n = amount;
        HarborId id = d.id();
        owned.computeIfAbsent(uuid, u -> new HashMap<>()).merge(id, -n, Integer::sum);
        var _ =
                kit.api()
                        .database()
                        .asyncVoid(c -> HousingRepository.takeDecor(c, uuid, id, n))
                        .whenCompleteAsync(
                                (v, error) -> {
                                    if (error != null) {
                                        owned.computeIfAbsent(uuid, u -> new HashMap<>())
                                                .merge(id, n, Integer::sum);
                                        kit.send(player, "world.decor.take_failed");
                                        return;
                                    }
                                    // 이동 저장본을 뜬 뒤면 인벤토리에 넣어도 저장되지 않으므로 보관함으로 되돌린다
                                    if (!player.isOnline() || kit.api().players().frozen(uuid)) {
                                        addCount(uuid, id, n);
                                        return;
                                    }
                                    ItemStack item = item(d, n);
                                    var left = player.getInventory().addItem(item);
                                    left.values()
                                            .forEach(rest -> addCount(uuid, id, rest.getAmount()));
                                    // 보관함 차감은 이미 커밋됐다: 인벤토리도 곧 저장한다 (서버가 죽으면 아이템 유실)
                                    kit.api().players().saveSoon(uuid);
                                },
                                kit.tasks().main());
    }

    private ItemStack item(DecorDef d, int amount) {
        ItemStack stack = coreItem(d, amount).orElse(null);
        if (stack == null) {
            stack =
                    Icons.of(
                            base(d),
                            Component.text(d.name()),
                            List.of(kit.api().messages().get("world.decor.lore.item")),
                            d.model());
        }
        stack.setAmount(amount);
        stack.editPersistentDataContainer(
                pdc -> pdc.set(tag(), PersistentDataType.STRING, d.id().toString()));
        return stack;
    }

    /** Core 가구·커스텀 블록 아이템 (장식 태그는 item()이 붙인다). 이름은 장식 이름으로 둔다. */
    private Optional<ItemStack> coreItem(DecorDef d, int amount) {
        HarborId furniture = d.furniture();
        HarborId block = d.block();
        Optional<ItemStack> stack =
                furniture != null
                        ? kit.api().furniture().furnitureItem(furniture)
                        : block != null
                                ? kit.api().furniture().blockItem(block, amount)
                                : Optional.empty();
        stack.ifPresent(
                s ->
                        s.setData(
                                io.papermc.paper.datacomponent.DataComponentTypes.ITEM_NAME,
                                Component.text(d.name())));
        return stack;
    }

    /** 플롯 안 Core 가구: 플롯 건축 권한이 있으면 회수를 허락한다. 장식으로 산 가구는 회수한 사람이 아니라 플롯 주인의 보관함 개수로 돌린다. */
    @EventHandler(ignoreCancelled = true)
    public void onFurnitureRemove(FurnitureRemoveEvent event) {
        Optional<HousingManager.PlotState> plot = housing.stateAt(event.location());
        if (plot.isEmpty()) {
            return;
        }
        if (housing.can(event.player(), plot.get().info, PlotPermission.BUILD)) {
            event.setAllowed(true);
        }
        Optional<DecorDef> d = def(event.item());
        if (d.isEmpty() || !event.allowed()) {
            return;
        }
        event.setReturnItem(false);
        World world = event.location().getWorld();
        if (world != null) {
            creditAfterWorldSave(world, plot.get().info.owner(), d.get().id(), 1);
        } else {
            addCount(plot.get().info.owner(), d.get().id(), 1);
        }
    }

    // ------------------------------------------------------------------ 설치·회수

    private NamespacedKey blockKey(Block block) {
        return kit.key("d_" + (block.getX() & 15) + "_" + block.getY() + "_" + (block.getZ() & 15));
    }

    private Optional<DecorDef> placedAt(Block block) {
        String raw =
                block.getChunk()
                        .getPersistentDataContainer()
                        .get(blockKey(block), PersistentDataType.STRING);
        return raw == null ? Optional.empty() : HarborId.tryParse(raw).flatMap(this::def);
    }

    private boolean tagged(Block block) {
        return block.getChunk().getPersistentDataContainer().has(blockKey(block));
    }

    /** 그 자리 블록이 아직 그 장식인지 (피스톤·액체 등으로 바뀌었으면 태그만 남은 것). */
    private boolean matches(Block block, DecorDef d) {
        HarborId custom = d.block();
        if (custom != null) {
            return kit.api().furniture().blockAt(block).map(custom::equals).orElse(false);
        }
        String raw = d.material();
        Material m = raw == null ? null : Material.matchMaterial(raw);
        // 벽 횃불처럼 놓으면 종류가 바뀌는 블록은 설치 아이템으로 비교한다
        return m != null
                && (block.getType() == m || block.getBlockData().getPlacementMaterial() == m);
    }

    /** 장식이 아닌 것이 놓인 자리의 남은 태그를 지운다 (수조면 어항 자리도). */
    private void clearStale(Block block) {
        Optional<DecorDef> d = placedAt(block);
        block.getChunk().getPersistentDataContainer().remove(blockKey(block));
        if (d.isPresent() && d.get().kind() == DecorDef.Kind.TANK) {
            housing.stateAt(block.getLocation()).ifPresent(p -> removeAnchorAt(p, block));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Optional<DecorDef> d = def(event.getItemInHand());
        Block block = event.getBlockPlaced();
        if (d.isEmpty()) {
            if (tagged(block)) {
                clearStale(block);
            }
            return;
        }
        Optional<HousingManager.PlotState> plot = housing.stateAt(block.getLocation());
        if (plot.isEmpty()
                || (d.get().kind() == DecorDef.Kind.FURNITURE && d.get().furniture() == null)) {
            event.setCancelled(true);
            return;
        }
        if (d.get().kind() == DecorDef.Kind.FURNITURE) {
            return; // Core 가구 설치 확인 (블록을 놓지 않는다)
        }
        if (d.get().kind() == DecorDef.Kind.TANK
                && plot.get().anchors.size() >= kit.balance().housingAnchorLimit()) {
            event.setCancelled(true);
            kit.actionBar(
                    event.getPlayer(),
                    "world.decor.tank_limit",
                    Placeholder.unparsed(
                            "limit", String.valueOf(kit.balance().housingAnchorLimit())));
            return;
        }
        block.getChunk()
                .getPersistentDataContainer()
                .set(blockKey(block), PersistentDataType.STRING, d.get().id().toString());
        if (d.get().kind() == DecorDef.Kind.TANK) {
            addAnchor(event.getPlayer(), plot.get(), d.get(), block);
        }
    }

    private void addAnchor(Player player, HousingManager.PlotState plot, DecorDef d, Block block) {
        int slot = 0;
        while (true) {
            int s = slot;
            if (plot.anchors.stream().noneMatch(a -> a.slot() == s)) {
                break;
            }
            slot++;
        }
        BlockFace face = player.getFacing().getOppositeFace();
        DisplayAnchor anchor =
                new DisplayAnchor(
                        plot.info.plotId(),
                        slot,
                        d.id(),
                        plot.info.world(),
                        block.getX(),
                        block.getY(),
                        block.getZ(),
                        face.name());
        housing.addAnchor(plot, anchor);
        var _ = kit.api().database().asyncVoid(c -> HousingRepository.addAnchor(c, anchor));
        Bukkit.getPluginManager().callEvent(new DisplayAnchorChangedEvent(plot.info, anchor, true));
        kit.actionBar(
                player,
                "world.decor.tank_placed",
                Placeholder.unparsed("slot", String.valueOf(slot + 1)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Optional<DecorDef> d = placedAt(block);
        if (d.isEmpty()) {
            return;
        }
        if (!matches(block, d.get())) {
            clearStale(block); // 장식은 이미 옮겨졌다: 회수하지 않고 평범하게 부순다
            return;
        }
        block.getChunk().getPersistentDataContainer().remove(blockKey(block));
        event.setDropItems(false);
        Optional<HousingManager.PlotState> plot = housing.stateAt(block.getLocation());
        UUID owner = plot.map(p -> p.info.owner()).orElse(event.getPlayer().getUniqueId());
        creditAfterWorldSave(block.getWorld(), owner, d.get().id(), 1);
        if (d.get().kind() == DecorDef.Kind.TANK && plot.isPresent()) {
            removeAnchorAt(plot.get(), block);
        }
    }

    private void removeAnchorAt(HousingManager.PlotState plot, Block block) {
        plot.anchors.stream()
                .filter(
                        a ->
                                a.x() == block.getX()
                                        && a.y() == block.getY()
                                        && a.z() == block.getZ())
                .findFirst()
                .ifPresent(
                        a -> {
                            housing.removeAnchor(plot, a.slot());
                            long id = a.plotId();
                            int slot = a.slot();
                            var _ =
                                    kit.api()
                                            .database()
                                            .asyncVoid(
                                                    c ->
                                                            HousingRepository.removeAnchor(
                                                                    c, id, slot));
                            Bukkit.getPluginManager()
                                    .callEvent(new DisplayAnchorChangedEvent(plot.info, a, false));
                        });
    }

    // 장식 태그는 위치에 붙어 있어 블록이 옮겨지면 태그만 남는다: 장식 블록을 밀거나 당기거나 액체로 쓸어 가지 못하게 한다
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) {
        if (!housing.isHousingWorld(event.getBlock().getWorld())) {
            return;
        }
        BlockFace dir = event.getDirection();
        if (tagged(event.getBlock().getRelative(dir))) {
            event.setCancelled(true); // 머리 자리의 부서지는 장식 (횃불 등)
            return;
        }
        for (Block b : event.getBlocks()) {
            if (tagged(b) || tagged(b.getRelative(dir))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent event) {
        if (!housing.isHousingWorld(event.getBlock().getWorld())) {
            return;
        }
        for (Block b : event.getBlocks()) {
            if (tagged(b)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        Block to = event.getToBlock();
        if (housing.isHousingWorld(to.getWorld()) && tagged(to)) {
            event.setCancelled(true);
        }
    }

    /** 가구 설치: 가구 장식을 들고 플롯 안 블록을 우클릭. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        Optional<DecorDef> d = def(hand);
        Block clicked = event.getClickedBlock();
        if (d.isEmpty()
                || d.get().kind() != DecorDef.Kind.FURNITURE
                || d.get().furniture() != null
                || clicked == null) {
            return; // Core 가구(furniture:)는 Core가 놓는다
        }
        event.setCancelled(true);
        Block target = clicked.getRelative(event.getBlockFace());
        Optional<HousingManager.PlotState> plot = housing.stateAt(target.getLocation());
        if (plot.isEmpty()
                || !housing.can(player, plot.get().info, PlotPermission.BUILD)
                || !target.getType().isAir()) {
            kit.actionBar(player, "world.housing.no_build");
            return;
        }
        World world = target.getWorld();
        Location at = target.getLocation().add(0.5, 0, 0.5);
        at.setYaw(Math.round(WorldKit.loc(player).getYaw() / 90f) * 90f + 180f);
        String model = d.get().model();
        ItemStack look = ItemStack.of(Material.PAPER);
        ItemModels.apply(look, model);
        String decorId = d.get().id().toString();
        ItemDisplay display =
                world.spawn(
                        at.clone().add(0, 0.5, 0),
                        ItemDisplay.class,
                        e -> {
                            e.setItemStack(look);
                            e.setPersistent(true);
                            e.getPersistentDataContainer()
                                    .set(tag(), PersistentDataType.STRING, decorId);
                        });
        world.spawn(
                at,
                Interaction.class,
                e -> {
                    e.setInteractionWidth(0.9f);
                    e.setInteractionHeight(1.0f);
                    e.setPersistent(true);
                    e.getPersistentDataContainer().set(tag(), PersistentDataType.STRING, decorId);
                    e.getPersistentDataContainer()
                            .set(
                                    kit.key("decor_display"),
                                    PersistentDataType.STRING,
                                    display.getUniqueId().toString());
                });
        hand.setAmount(hand.getAmount() - 1);
    }

    /** 가구 회수: 웅크리고 우클릭 (건축 권한). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFurniture(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Interaction box)) {
            return;
        }
        String decorId = box.getPersistentDataContainer().get(tag(), PersistentDataType.STRING);
        if (decorId == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Optional<HousingManager.PlotState> plot = housing.stateAt(WorldKit.loc(box));
        if (!player.isSneaking()
                || plot.isEmpty()
                || !housing.can(player, plot.get().info, PlotPermission.BUILD)) {
            return;
        }
        String displayId =
                box.getPersistentDataContainer()
                        .get(kit.key("decor_display"), PersistentDataType.STRING);
        if (displayId != null) {
            try {
                Entity display = Bukkit.getEntity(UUID.fromString(displayId));
                if (display != null) {
                    display.remove();
                }
            } catch (IllegalArgumentException e) {
                // 잘못된 UUID는 무시
            }
        }
        World world = box.getWorld();
        box.remove();
        HarborId.tryParse(decorId)
                .ifPresent(id -> creditAfterWorldSave(world, plot.get().info.owner(), id, 1));
        kit.actionBar(player, "world.decor.furniture_removed");
    }

    // ------------------------------------------------------------------ 하우징 월드를 나갈 때

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        load(event.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        if (housing.isHousingWorld(event.getFrom())) {
            stash(event.getPlayer());
        }
    }

    /**
     * 퇴장 저장(MONITOR)보다 먼저 보관함으로 돌린다. 서버 이동이면 저장본을 이미 떴으므로 (퇴장 때 다시 저장하지 않음) 여기서 옮기면 저장본과 보관함 양쪽에
     * 남는다: 이동은 {@link #handoffBlocker()}가 저장 전에 처리하고, 여기서는 건너뛴다.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (housing.isHousingWorld(player.getWorld())
                && !kit.api().players().frozen(player.getUniqueId())) {
            stash(player);
        }
        owned.remove(player.getUniqueId());
    }

    /**
     * 서버 이동 전 (저장본을 뜨기 전, 메인 스레드): 하우징 월드에서 떠나면 장식 아이템을 보관함으로 돌린다. 이동하지 않는 훅이라 항상 허용한다. 다른 훅이 이동을
     * 거부해도 장식은 보관함에 들어가 있을 뿐이다 (다시 꺼내면 된다). World 훅 중 마지막에 등록한다.
     */
    public HandoffBlocker handoffBlocker() {
        return (player, destination) -> {
            if (housing.isHousingWorld(player.getWorld())) {
                stash(player);
            }
            return HandoffBlocker.ALLOW;
        };
    }

    /** 가방의 장식 아이템을 보관함 개수로 돌려놓는다. */
    private void stash(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        boolean moved = false;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            Optional<DecorDef> d = def(stack);
            if (d.isPresent() && stack != null) {
                addCount(player.getUniqueId(), d.get().id(), stack.getAmount());
                player.getInventory().setItem(i, null);
                moved = true;
            }
        }
        if (moved) {
            // 보관함 개수는 즉시 쓰고 인벤토리는 지연 쓰기라, 서버가 죽으면 양쪽에 남는다 (퇴장 때는 곧이어 퇴장 저장이 돈다)
            kit.api().players().saveSoon(player.getUniqueId());
        }
    }
}
