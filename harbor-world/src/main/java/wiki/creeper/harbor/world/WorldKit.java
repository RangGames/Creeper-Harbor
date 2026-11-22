package wiki.creeper.harbor.world;

import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.world.content.WorldBalance;
import wiki.creeper.harbor.world.region.WorldIndex;

/**
 * Shared tools of the World module: Core API, scheduled tasks, region index (replaced wholesale on
 * reload), world name mapping (balance.yml world_alias), item checks. Use on the main thread
 * ({@link #index()} and {@link #balance()} from anywhere).
 */
public final class WorldKit {

    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private volatile WorldIndex index = WorldIndex.EMPTY;

    public WorldKit(Plugin plugin, HarborApi api, Tasks tasks, Logger log) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.log = log;
    }

    public Plugin plugin() {
        return plugin;
    }

    public HarborApi api() {
        return api;
    }

    public Tasks tasks() {
        return tasks;
    }

    public Logger log() {
        return log;
    }

    public WorldIndex index() {
        return index;
    }

    void setIndex(WorldIndex next) {
        index = next;
    }

    public WorldBalance balance() {
        return new WorldBalance(api.content().snapshot().single(Balance.TYPE));
    }

    public NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }

    public int season() {
        return api.infra().season().value();
    }

    /**
     * Bukkit world → content world name (balance.yml world_alias, e.g. world → harbor_main on a dev
     * server).
     */
    public String worldId(World world) {
        return balance().worldAlias(world.getName());
    }

    public String worldId(Location location) {
        World world = location.getWorld();
        return world == null ? "" : worldId(world);
    }

    /** Content world name → this server's Bukkit world (null = it lives on another server). */
    public @Nullable World bukkitWorld(String worldId) {
        World direct = Bukkit.getWorld(worldId);
        if (direct != null) {
            return direct;
        }
        for (World w : Bukkit.getWorlds()) {
            if (worldId(w).equals(worldId)) {
                return w;
            }
        }
        return null;
    }

    /** A living entity always has a location (Paper marks it as nullable). */
    public static Location loc(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }

    public double stat(Player player, String key, double base) {
        return api.modifiers().apply(player.getUniqueId(), StatKey.of(key), base);
    }

    /** Harbor general item ID (empty for fish species and vanilla items). */
    public Optional<ItemId> itemId(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Plain)
                .map(i -> ((HarborItem.Plain) i).id());
    }

    /**
     * Whether the item is in the inventory or an equipment slot ('1. design doc' chapter 2 item
     * conditions).
     */
    public boolean hasItem(Player player, ItemId item) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (itemId(stack).filter(item::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Whether it is worn in an equipment (armor) slot. */
    public boolean wearing(Player player, ItemId item) {
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            if (itemId(stack).filter(item::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    public String itemName(ItemId item) {
        return api.content()
                .snapshot()
                .get(ItemDef.TYPE, item.id())
                .map(ItemDef::name)
                .orElse(item.id().name());
    }

    public Optional<ItemStack> createItem(HarborId item, int amount) {
        return api.content()
                .snapshot()
                .get(ItemDef.TYPE, item)
                .map(def -> api.items().create(def, amount));
    }

    /** Puts it in the inventory and drops the overflow at the player's feet. */
    public void give(Player player, ItemStack stack) {
        player.getInventory()
                .addItem(stack)
                .values()
                .forEach(left -> player.getWorld().dropItem(loc(player), left));
    }

    public void send(Player player, String key, TagResolver... resolvers) {
        api.messages().send(player, key, resolvers);
    }

    public void actionBar(Player player, String key, TagResolver... resolvers) {
        api.ui().actionBar(player, api.messages().get(key, resolvers));
    }
}
