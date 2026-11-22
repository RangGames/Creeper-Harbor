package wiki.creeper.harbor.world.housing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.world.WorldKit;
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
    private final Map<UUID, Map<HarborId, Integer>> owned = new HashMap<>();
    private final DecorShop shop;
    private final DecorPlacement placement;

    public DecorManager(
            WorldKit kit,
            HousingManager housing,
            Payments payments,
            Function<UUID, CompletableFuture<Integer>> bestTier) {
        this.kit = kit;
        this.housing = housing;
        this.shop = new DecorShop(kit, housing, payments, bestTier, this);
        this.placement = new DecorPlacement(kit, housing, this);
    }

    public void start() {
        Bukkit.getOnlinePlayers().forEach(this::load);
    }

    NamespacedKey tag() {
        return kit.key("decor");
    }

    Optional<DecorDef> def(HarborId id) {
        return kit.api().content().snapshot().get(DecorDef.TYPE, id);
    }

    Optional<DecorDef> def(@Nullable ItemStack stack) {
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

    void addCount(UUID uuid, HarborId decor, int amount) {
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
    void creditAfterWorldSave(World world, UUID owner, HarborId decor, int amount) {
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

    /** Adjusts the in-memory owned count only (the caller writes the DB). Main thread only. */
    void adjustOwned(UUID uuid, HarborId decor, int delta) {
        owned.computeIfAbsent(uuid, u -> new HashMap<>()).merge(decor, delta, Integer::sum);
    }

    /** In-memory owned counts of a player (decor → count). Main thread only. */
    Map<HarborId, Integer> ownedOf(UUID uuid) {
        return owned.getOrDefault(uuid, Map.of());
    }

    /** Placement/removal listener; register it alongside this manager. */
    public DecorPlacement placement() {
        return placement;
    }

    // ------------------------------------------------------------------ 상점

    /** 장식 상점 (/부두 상점). */
    public void openShop(Player player) {
        shop.openShop(player);
    }

    // ------------------------------------------------------------------ 팔레트

    /** 장식 팔레트 (/부두 장식): 건축할 수 있는 플롯 안에서만. 클릭 1개, 쉬프트 클릭 16개. */
    public void openPalette(Player player) {
        shop.openPalette(player);
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
