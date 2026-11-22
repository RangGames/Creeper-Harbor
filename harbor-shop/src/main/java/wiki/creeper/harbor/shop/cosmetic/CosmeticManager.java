package wiki.creeper.harbor.shop.cosmetic;

import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.shop.ShopRuntime;
import wiki.creeper.harbor.shop.api.CosmeticService;
import wiki.creeper.harbor.shop.api.event.CosmeticEquipEvent;
import wiki.creeper.harbor.shop.api.event.CosmeticGrantedEvent;
import wiki.creeper.harbor.shop.content.CosmeticDef;
import wiki.creeper.harbor.shop.data.ShopRepository;

/**
 * Cosmetic ownership, equipping and rod skins (CosmeticService implementation, docs 20 12.4
 * 'cosmetics'). A rod skin only re-skins the item_model of the wearer's Harbor rod items and writes
 * "skin ID|owner UUID" to PDC harbor:skin. Stats stay unchanged. Removed when someone other than
 * the skin owner holds or picks it up.
 */
public final class CosmeticManager implements CosmeticService, Listener {

    private record Suppressor(Plugin owner, Predicate<Player> hide) {}

    private final ShopRuntime rt;
    private final NamespacedKey skinKey = new NamespacedKey("harbor", "skin");
    private final List<Suppressor> suppressors = new CopyOnWriteArrayList<>();

    public CosmeticManager(ShopRuntime rt) {
        this.rt = rt;
    }

    @Override
    public boolean owns(UUID player, HarborId cosmetic) {
        return rt.profile(player).owns(cosmetic);
    }

    @Override
    public Set<HarborId> owned(UUID player) {
        return rt.profile(player).owned();
    }

    @Override
    public Optional<HarborId> equipped(UUID player, CosmeticDef.Category slot) {
        return Optional.ofNullable(rt.profile(player).equipped().get(slot));
    }

    @Override
    public CompletableFuture<Boolean> grant(
            UUID player, HarborId cosmetic, String source, String refId) {
        if (rt.cosmetic(cosmetic).isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        return rt.db(c -> ShopRepository.insertOwned(c, player, cosmetic, source, refId))
                .thenApply(
                        added -> {
                            if (added) {
                                granted(player, Set.of(cosmetic), source);
                                if (rt.online(player).isEmpty()) {
                                    rt.notifyPlayer(player, "COSMETIC", 0);
                                }
                            }
                            return added;
                        });
    }

    /** Applies a committed grant to the cache and events (main thread). */
    public void granted(UUID player, Set<HarborId> cosmetics, String source) {
        if (cosmetics.isEmpty()) {
            return;
        }
        rt.update(player, p -> p.plusOwned(cosmetics));
        if (rt.online(player).isPresent()) {
            cosmetics.forEach(c -> rt.call(new CosmeticGrantedEvent(player, c, source)));
        }
    }

    @Override
    public CompletableFuture<Boolean> revoke(UUID player, HarborId cosmetic) {
        return rt.db(c -> ShopRepository.deleteOwned(c, player, cosmetic))
                .thenApply(
                        removed -> {
                            Player online = Bukkit.getPlayer(player);
                            rt.cosmetic(cosmetic)
                                    .ifPresent(
                                            def -> {
                                                if (online != null
                                                        && cosmetic.equals(
                                                                rt.profile(player)
                                                                        .equipped()
                                                                        .get(def.category()))) {
                                                    unequip(online, def.category());
                                                }
                                            });
                            Set<HarborId> rest = new HashSet<>(rt.profile(player).owned());
                            rest.remove(cosmetic);
                            rt.update(player, p -> p.withOwned(rest));
                            return removed;
                        });
    }

    /** Re-reads cosmetics granted on another server. */
    public CompletableFuture<Void> refresh(UUID player) {
        return rt.db(c -> ShopRepository.owned(c, player))
                .thenAccept(
                        owned -> {
                            Set<HarborId> before = rt.profile(player).owned();
                            rt.update(player, p -> p.withOwned(owned));
                            Set<HarborId> added = new HashSet<>(owned);
                            added.removeAll(before);
                            added.forEach(
                                    c -> rt.call(new CosmeticGrantedEvent(player, c, "REMOTE")));
                        });
    }

    @Override
    public boolean equip(Player player, HarborId cosmetic) {
        UUID uuid = player.getUniqueId();
        Optional<CosmeticDef> def = rt.cosmetic(cosmetic);
        if (def.isEmpty() || !owns(uuid, cosmetic)) {
            return false;
        }
        setSlot(player, def.get().category(), cosmetic);
        return true;
    }

    @Override
    public void unequip(Player player, CosmeticDef.Category slot) {
        setSlot(player, slot, null);
    }

    private void setSlot(Player player, CosmeticDef.Category slot, @Nullable HarborId cosmetic) {
        UUID uuid = player.getUniqueId();
        HarborId previous = rt.profile(uuid).equipped().get(slot);
        if (cosmetic == null ? previous == null : cosmetic.equals(previous)) {
            return;
        }
        rt.update(uuid, p -> p.withEquipped(slot, cosmetic));
        var _ =
                rt.api()
                        .database()
                        .asyncVoid(c -> ShopRepository.setEquipped(c, uuid, slot, cosmetic))
                        .exceptionally(
                                e -> {
                                    rt.log().error("장착 저장 실패 {} {}", uuid, slot, e);
                                    return null;
                                });
        if (slot == CosmeticDef.Category.ROD_SKIN) {
            applySkins(player);
        }
        rt.call(new CosmeticEquipEvent(player, slot, previous, cosmetic));
    }

    // ------------------------------------------------------------------ rod skins

    @Override
    public Optional<HarborId> skinOf(ItemStack stack) {
        String raw = stack.getPersistentDataContainer().get(skinKey, PersistentDataType.STRING);
        if (raw == null) {
            return Optional.empty();
        }
        int bar = raw.indexOf('|');
        return HarborId.tryParse(bar < 0 ? raw : raw.substring(0, bar));
    }

    private Optional<UUID> skinOwner(ItemStack stack) {
        String raw = stack.getPersistentDataContainer().get(skinKey, PersistentDataType.STRING);
        if (raw == null || raw.indexOf('|') < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.substring(raw.indexOf('|') + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean stripSkin(ItemStack stack) {
        if (stack.isEmpty() || skinOf(stack).isEmpty()) {
            return false;
        }
        Optional<ItemDef> def = rodDef(stack);
        stack.editPersistentDataContainer(pdc -> pdc.remove(skinKey));
        stack.resetData(DataComponentTypes.ITEM_MODEL);
        if (def.isPresent()) {
            ItemModels.apply(stack, def.get().model());
        }
        return true;
    }

    /** The definition, if it is a Harbor rod. */
    private Optional<ItemDef> rodDef(ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        return rt.api()
                .items()
                .read(stack)
                .flatMap(
                        item ->
                                item instanceof HarborItem.Plain plain
                                        ? rt.snapshot().get(ItemDef.TYPE, plain.id().id())
                                        : Optional.empty())
                .filter(d -> d.type() == ItemDef.Type.ROD);
    }

    /** Applies or removes the equipped skin on rods in the player's inventory (main thread). */
    public void applySkins(Player player) {
        HarborId skin =
                rt.profile(player.getUniqueId()).equipped().get(CosmeticDef.Category.ROD_SKIN);
        String model = skin == null ? null : rt.cosmetic(skin).map(CosmeticDef::model).orElse(null);
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null || rodDef(stack).isEmpty()) {
                continue;
            }
            applySkin(stack, player.getUniqueId(), skin, model);
        }
    }

    private void applySkin(
            ItemStack stack, UUID owner, @Nullable HarborId skin, @Nullable String model) {
        if (skin == null || model == null) {
            stripSkin(stack);
            return;
        }
        String value = skin + "|" + owner;
        if (value.equals(
                stack.getPersistentDataContainer().get(skinKey, PersistentDataType.STRING))) {
            return;
        }
        stack.editPersistentDataContainer(
                pdc -> pdc.set(skinKey, PersistentDataType.STRING, value));
        ItemModels.apply(stack, model);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack stack = player.getInventory().getItem(event.getNewSlot());
        if (stack == null || rodDef(stack).isEmpty()) {
            return;
        }
        Optional<UUID> owner = skinOwner(stack);
        HarborId skin =
                rt.profile(player.getUniqueId()).equipped().get(CosmeticDef.Category.ROD_SKIN);
        if (owner.isPresent() && !owner.get().equals(player.getUniqueId())) {
            stripSkin(stack);
        }
        applySkin(
                stack,
                player.getUniqueId(),
                skin,
                skin == null ? null : rt.cosmetic(skin).map(CosmeticDef::model).orElse(null));
    }

    /** A skinned rod picked up by someone else reverts to the original model (docs 20 12.4). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        Optional<UUID> owner = skinOwner(stack);
        if (owner.isPresent() && !owner.get().equals(player.getUniqueId())) {
            stripSkin(stack);
            event.getItem().setItemStack(stack);
        }
    }

    // ------------------------------------------------------------------ pet hide conditions

    @Override
    public void registerPetSuppressor(Plugin owner, Predicate<Player> hide) {
        suppressors.add(new Suppressor(owner, hide));
    }

    /** Whether pets and ship decorations should be hidden. */
    public boolean suppressed(Player player) {
        suppressors.removeIf(s -> !s.owner().isEnabled());
        for (Suppressor s : suppressors) {
            try {
                if (s.hide().test(player)) {
                    return true;
                }
            } catch (RuntimeException e) {
                rt.log().warn("펫 숨김 조건 오류 ({})", s.owner().getName(), e);
            }
        }
        return false;
    }

    /** Equip slot table (wardrobe screen). */
    public Map<CosmeticDef.Category, HarborId> equippedAll(UUID player) {
        return rt.profile(player).equipped();
    }
}
