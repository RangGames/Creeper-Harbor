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

/** 아이템·월드·PDC 도우미 (서버 API). */
public final class BossItems {

    /** 지급 완료 표시 (플레이어 PDC, 아이템과 같은 파일에 저장된다). */
    public static final NamespacedKey DELIVERED = new NamespacedKey("harbor", "boss_delivered");

    /** 보스 장비 고유 ID (추가 제안: fish_ledger item_id 연동 전까지 아이템 PDC에 둔다). */
    public static final NamespacedKey BOSS_UID = new NamespacedKey("harbor", "boss_uid");

    /** PDC에 남기는 지급 완료 ID 수 (오래된 것부터 지운다, 제안값). */
    private static final int DELIVERED_KEEP = 200;

    /** 작살 피해 스탯 키 (items/*.yml stats). */
    public static final String STAT_HARPOON = "harpoon_damage";

    /** 약점 피해 보너스 (고래잡이 작살 +50%). */
    public static final String STAT_WEAK_BONUS = "weak_bonus";

    /** 1이면 맞힌 뒤 회수 (인벤토리로 돌아옴). */
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

    /** 작살이면 아이템 정의 (stats에 harpoon_damage가 있는 아이템). */
    public Optional<ItemDef> harpoon(@Nullable ItemStack stack) {
        return defOf(stack).filter(d -> d.stats().containsKey(STAT_HARPOON));
    }

    public static double stat(ItemDef def, String key, double fallback) {
        Double v = def.stats().get(key);
        return v == null ? fallback : v;
    }

    /** 수리 키트 아이템 ID (balance.yml boss.items.repair_kit). */
    public ItemId repairKit() {
        String raw = balance().string("boss.items.repair_kit").orElse("item/boat/repair_kit");
        return new ItemId(HarborId.parse(raw));
    }

    public boolean isRepairKit(@Nullable ItemStack stack) {
        return idOf(stack).map(id -> id.equals(repairKit())).orElse(false);
    }

    /** 인벤토리에서 아이템 하나를 뺀다. 없으면 false. */
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

    // ------------------------------------------------------------------ 지급 완료 표시 (PDC)

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

    // ------------------------------------------------------------------ 월드

    /**
     * 콘텐츠 월드 ID(harbor_main 등) → Bukkit 월드. 개발 서버는 balance.yml world_alias (Fishing·NPC와 같은 규칙).
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

    /** Paper의 위치 조회는 null 가능으로 표시되어 있지만 살아 있는 엔티티는 항상 위치가 있다. */
    public static Location loc(Entity entity) {
        return Objects.requireNonNull(entity.getLocation());
    }

    public static Location eye(Player player) {
        return Objects.requireNonNull(player.getEyeLocation());
    }
}
