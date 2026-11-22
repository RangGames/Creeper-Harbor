package wiki.creeper.harbor.boss.encounter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;

/** Item, world and PDC helpers (server API). */
public final class BossItems {

    /** Delivered marker (player PDC, saved in the same file as items). */
    public static final NamespacedKey DELIVERED = new NamespacedKey("harbor", "boss_delivered");

    /** Unique boss gear ID (proposed: kept in item PDC until fish_ledger item_id integration). */
    public static final NamespacedKey BOSS_UID = new NamespacedKey("harbor", "boss_uid");

    /** Number of delivered IDs kept in PDC (oldest removed first, proposed value). */
    private static final int DELIVERED_KEEP = 200;

    /** Harpoon damage stat key (items/*.yml stats). */
    public static final String STAT_HARPOON = "harpoon_damage";

    /** Weak point damage bonus (whaler's harpoon +50%). */
    public static final String STAT_WEAK_BONUS = "weak_bonus";

    /** 1 means it is retrieved after hitting (returns to the inventory). */
    public static final String STAT_RECOVER = "recoverable";

    private final HarborApi api;

    public BossItems(HarborApi api) {
        this.api = api;
    }

    public Optional<ItemId> idOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .flatMap(
                        h ->
                                h instanceof HarborItem.Plain p
                                        ? Optional.of(p.id())
                                        : Optional.empty());
    }

    public Optional<ItemDef> def(ItemId id) {
        return api.content().snapshot().get(ItemDef.TYPE, id.id());
    }

    public Optional<ItemDef> defOf(@Nullable ItemStack stack) {
        return idOf(stack).flatMap(this::def);
    }

    /** Item definition if it is a harpoon (an item with harpoon_damage in stats). */
    public Optional<ItemDef> harpoon(@Nullable ItemStack stack) {
        return defOf(stack).filter(d -> d.stats().containsKey(STAT_HARPOON));
    }

    public static double stat(ItemDef def, String key, double fallback) {
        Double v = def.stats().get(key);
        return v == null ? fallback : v;
    }

    /** Repair kit item ID (balance.yml boss.items.repair_kit). */
    public ItemId repairKit() {
        String raw = balance().string("boss.items.repair_kit").orElse("item/boat/repair_kit");
        return new ItemId(HarborId.parse(raw));
    }

    public boolean isRepairKit(@Nullable ItemStack stack) {
        return idOf(stack).map(id -> id.equals(repairKit())).orElse(false);
    }

    /** Removes one item from the inventory. False if there is none. */
    public boolean takeOne(Player player, ItemId id) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack s = contents[i];
            if (s != null && idOf(s).map(id::equals).orElse(false)) {
                s.setAmount(s.getAmount() - 1);
                player.getInventory().setItem(i, s.getAmount() <= 0 ? null : s);
                return true;
            }
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (idOf(off).map(id::equals).orElse(false)) {
            off.setAmount(off.getAmount() - 1);
            player.getInventory().setItemInOffHand(off.getAmount() <= 0 ? null : off);
            return true;
        }
        return false;
    }

    public Optional<ItemStack> create(ItemId id, int amount) {
        return def(id).map(d -> api.items().create(d, amount));
    }

    // ------------------------------------------------------------------ Delivered marker (PDC)

    public static Set<String> delivered(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        String raw = pdc.get(DELIVERED, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return new LinkedHashSet<>(Arrays.asList(raw.split(",")));
    }

    public static void markDelivered(Player player, List<String> ids) {
        Set<String> all = delivered(player);
        all.addAll(ids);
        List<String> list = new ArrayList<>(all);
        if (list.size() > DELIVERED_KEEP) {
            list = list.subList(list.size() - DELIVERED_KEEP, list.size());
        }
        player.getPersistentDataContainer()
                .set(DELIVERED, PersistentDataType.STRING, String.join(",", list));
    }

    // ------------------------------------------------------------------ World

    /**
     * Content world ID (harbor_main, etc.) → Bukkit world. Dev servers use balance.yml world_alias
     * (same rule as Fishing and NPC).
     */
    public Optional<World> world(String contentWorld) {
        World direct = Bukkit.getWorld(contentWorld);
        if (direct != null) {
            return Optional.of(direct);
        }
        Balance balance = balance();
        for (String alias : balance.keys("world_alias")) {
            if (balance.string("world_alias." + alias).orElse("").equals(contentWorld)) {
                World w = Bukkit.getWorld(alias);
                if (w != null) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }

    public String worldId(World world) {
        return balance().string("world_alias." + world.getName()).orElse(world.getName());
    }

    private Balance balance() {
        return api.content().snapshot().single(Balance.TYPE);
    }

    /** Paper marks location lookup as nullable, but a living entity always has a location. */
    public static Location loc(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }

    public static Location eye(Player player) {
        return Objects.requireNonNull(player.getEyeLocation());
    }
}
