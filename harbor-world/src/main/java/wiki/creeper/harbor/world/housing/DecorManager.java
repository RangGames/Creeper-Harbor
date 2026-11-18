package wiki.creeper.harbor.world.housing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.kyori.adventure.key.Key;
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
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.core.api.MessageService;
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
        return Icons.of(base(d), Component.text(d.name()), lore, d.model());
    }

    private ItemStack base(DecorDef d) {
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
                                    if (!player.isOnline()) {
                                        addCount(uuid, id, n);
                                        return;
                                    }
                                    ItemStack item = item(d, n);
                                    var left = player.getInventory().addItem(item);
                                    left.values()
                                            .forEach(rest -> addCount(uuid, id, rest.getAmount()));
                                },
                                kit.tasks().main());
    }

    private ItemStack item(DecorDef d, int amount) {
        ItemStack stack =
                Icons.of(
                        base(d),
                        Component.text(d.name()),
                        List.of(kit.api().messages().get("world.decor.lore.item")),
                        d.model());
        stack.setAmount(amount);
        stack.editPersistentDataContainer(
                pdc -> pdc.set(tag(), PersistentDataType.STRING, d.id().toString()));
        return stack;
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Optional<DecorDef> d = def(event.getItemInHand());
        if (d.isEmpty()) {
            return;
        }
        Block block = event.getBlockPlaced();
        Optional<HousingManager.PlotState> plot = housing.stateAt(block.getLocation());
        if (plot.isEmpty() || d.get().kind() == DecorDef.Kind.FURNITURE) {
            event.setCancelled(true);
            return;
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
        block.getChunk().getPersistentDataContainer().remove(blockKey(block));
        event.setDropItems(false);
        Optional<HousingManager.PlotState> plot = housing.stateAt(block.getLocation());
        UUID owner = plot.map(p -> p.info.owner()).orElse(event.getPlayer().getUniqueId());
        addCount(owner, d.get().id(), 1);
        if (d.get().kind() == DecorDef.Kind.TANK && plot.isPresent()) {
            plot.get().anchors.stream()
                    .filter(
                            a ->
                                    a.x() == block.getX()
                                            && a.y() == block.getY()
                                            && a.z() == block.getZ())
                    .findFirst()
                    .ifPresent(
                            a -> {
                                housing.removeAnchor(plot.get(), a.slot());
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
                                        .callEvent(
                                                new DisplayAnchorChangedEvent(
                                                        plot.get().info, a, false));
                            });
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
        if (d.isEmpty() || d.get().kind() != DecorDef.Kind.FURNITURE || clicked == null) {
            return;
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
        if (model != null && Key.parseable(model)) {
            look.setData(
                    io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL, Key.key(model));
        }
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
        box.remove();
        HarborId.tryParse(decorId).ifPresent(id -> addCount(plot.get().info.owner(), id, 1));
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

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (housing.isHousingWorld(player.getWorld())) {
            stash(player);
        }
        owned.remove(player.getUniqueId());
    }

    /** 가방의 장식 아이템을 보관함 개수로 돌려놓는다. */
    private void stash(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            Optional<DecorDef> d = def(stack);
            if (d.isPresent() && stack != null) {
                addCount(player.getUniqueId(), d.get().id(), stack.getAmount());
                player.getInventory().setItem(i, null);
            }
        }
    }
}
