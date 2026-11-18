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
 * World 모듈 공용 도구: Core API, 예약 작업, 지역 색인(리로드 때 통째로 교체), 월드 이름 대응(balance.yml world_alias), 아이템 확인.
 * 메인 스레드에서 쓴다 ({@link #index()}·{@link #balance()}는 어디서나).
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

    /** Bukkit 월드 → 콘텐츠 월드 이름 (balance.yml world_alias, 개발 서버의 world → harbor_main). */
    public String worldId(World world) {
        return balance().worldAlias(world.getName());
    }

    public String worldId(Location location) {
        World world = location.getWorld();
        return world == null ? "" : worldId(world);
    }

    /** 콘텐츠 월드 이름 → 이 서버의 Bukkit 월드 (없으면 null = 다른 서버에 있다). */
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

    /** 살아 있는 엔티티는 항상 위치가 있다 (Paper가 null 가능으로 표시). */
    public static Location loc(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }

    public double stat(Player player, String key, double base) {
        return api.modifiers().apply(player.getUniqueId(), StatKey.of(key), base);
    }

    /** Harbor 일반 아이템 ID (어종·바닐라 아이템이면 빈 값). */
    public Optional<ItemId> itemId(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Plain)
                .map(i -> ((HarborItem.Plain) i).id());
    }

    /** 인벤토리나 장비 칸에 그 아이템이 있는지 ('1. 기획서 본문' 2장 아이템 조건). */
    public boolean hasItem(Player player, ItemId item) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (itemId(stack).filter(item::equals).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** 장비 칸(갑옷)에 입고 있는지. */
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

    /** 가방에 넣고 넘치면 발밑에 떨어뜨린다. */
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
