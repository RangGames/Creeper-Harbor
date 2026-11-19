package wiki.creeper.harbor.core.inventory;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.config.ServerRole;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.player.PlayerManager;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 서버 간 인벤토리 동기화 (추가 제안, HuskSync의 Lockstep 동작을 참고해 Harbor 세션 잠금 위에 만든 것).
 *
 * <ul>
 *   <li>대상: 인벤토리(갑옷·왼손), 엔더 상자, 플레이어 PDC, 경험치, 체력·배고픔, 포션 효과(신호기 같은 주변 효과 제외), 선택한 핫바 칸
 *   <li>로드: 다른 플레이어 데이터와 같이 접속 전에 DB에서 읽는다 (세션 잠금을 넘겨받은 뒤라 이전 서버의 저장이 끝난 상태). 접속 즉시(다른 모듈보다 먼저)
 *       적용한다: 제작 칸·커서를 비우고 덮어쓴다
 *   <li>저장: 저장 훅으로 메인 스레드에서 뜬다. 퇴장·이동·종료 때는 열린 창을 닫아(커서·제작 칸 아이템을 인벤토리로) 뜬 뒤, 잠금을 넘기기 전에 DB에 쓴다 (지연
 *       쓰기 큐 flushNow)
 *   <li>처음 도입: DB에 행이 없는 플레이어는 본 서버(main)의 기존 인벤토리(플레이어 파일)를 원본으로 삼는다. 로비·인스턴스에서는 행이 생길 때까지 동기화하지
 *       않는다 (빈 로비 인벤토리가 원본을 덮지 않게)
 *   <li>기록: 퇴장·이동·종료·사망·복구 때 player_inventory_history에 남기고 최근 balance.yml inventory.history_keep개만
 *       둔다
 * </ul>
 *
 * balance.yml inventory.sync가 false면 아무것도 하지 않는다.
 */
public final class InventorySync implements Listener {

    /**
     * 컴포넌트 값.
     *
     * @param data DB의 지금 상태 (없으면 null = 아직 동기화한 적 없는 플레이어)
     */
    public record Synced(@Nullable InventoryData data) {}

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    private static final int CRAFTING_SLOTS = 5;

    private final PlayerManager players;
    private final Database db;
    private final WriteBehindQueue queue;
    private final Supplier<ContentSnapshot> content;
    private final MessageService messages;
    private final Tasks tasks;
    private final Logger log;
    private final String server;
    private final ServerRole role;
    private final PlayerComponent<Synced> component;

    /** 이 서버에서 동기화 중인 플레이어 (적용했거나 본 서버의 원본). */
    private final Set<UUID> synced = new HashSet<>();

    public InventorySync(
            PlayerManager players,
            Database db,
            WriteBehindQueue queue,
            Supplier<ContentSnapshot> content,
            MessageService messages,
            Tasks tasks,
            Logger log,
            String server,
            ServerRole role) {
        this.players = players;
        this.db = db;
        this.queue = queue;
        this.content = content;
        this.messages = messages;
        this.tasks = tasks;
        this.log = log;
        this.server = server;
        this.role = role;
        this.component =
                new PlayerComponent<>(
                        "inventory",
                        (c, uuid, season) ->
                                new Synced(InventoryRepository.load(c, uuid).orElse(null)),
                        (c, uuid, season, value) -> {
                            InventoryData data = value.data();
                            if (data != null) {
                                InventoryRepository.save(c, uuid, server, data.encode());
                            }
                        });
    }

    /** 컴포넌트·저장 훅 등록 (플레이어가 들어오기 전에). */
    public void register() {
        players.register(component);
        players.addSaveHook(this::beforeSave);
    }

    private boolean enabled() {
        return balance().bool("inventory.sync", true);
    }

    private Balance balance() {
        return content.get().single(Balance.TYPE);
    }

    private int keep() {
        return Math.max(1, balance().integer("inventory.history_keep", 20));
    }

    private static String prefix(UUID uuid) {
        return "player:" + uuid + ":";
    }

    // ------------------------------------------------------------------ 적용

    /** 다른 모듈의 접속 처리(시즌 정리·보상 지급 등)보다 먼저 적용한다. PlayerManager가 LOWEST에서 데이터를 붙인다. */
    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        synced.remove(uuid);
        if (!enabled()) {
            return;
        }
        HarborPlayer hp = players.get(uuid).orElse(null);
        if (hp == null) {
            return;
        }
        InventoryData data = hp.get(component).data();
        if (data != null) {
            try {
                apply(player, data);
                synced.add(uuid);
            } catch (RuntimeException e) {
                // 적용하지 못한 채 저장하면 원본을 덮으므로 동기화하지 않는다 (다음 접속 때 다시)
                log.error("인벤토리 적용 실패, 이 접속에서는 동기화하지 않습니다: {}", player.getName(), e);
                messages.send(player, "inventory.apply_failed");
            }
        } else if (role == ServerRole.MAIN) {
            // 처음: 본 서버의 기존 인벤토리가 원본 (다음 저장 때 행이 생긴다)
            synced.add(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        // PlayerManager(MONITOR)의 퇴장 저장이 끝난 뒤에 지운다
        var _ =
                tasks.later(
                        1,
                        () -> {
                            if (Bukkit.getPlayer(uuid) == null) {
                                synced.remove(uuid);
                            }
                        });
    }

    /** HuskSync BukkitData의 적용 순서를 따른다: 제작 칸·커서 비움 → 인벤토리 → 엔더 상자 → PDC → 효과 → 체력·배고픔·경험치. */
    static void apply(Player player, InventoryData data) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) {
            for (int i = 0; i < Math.min(CRAFTING_SLOTS, top.getSize()); i++) {
                top.setItem(i, null);
            }
        }
        player.setItemOnCursor(null);
        player.getInventory()
                .setContents(
                        fit(
                                ItemStack.deserializeItemsFromBytes(data.contents()),
                                player.getInventory().getSize()));
        Inventory ender = player.getEnderChest();
        ender.setContents(fit(ItemStack.deserializeItemsFromBytes(data.ender()), ender.getSize()));
        try {
            player.getPersistentDataContainer().readFromBytes(data.pdc(), true);
        } catch (IOException e) {
            throw new IllegalStateException("PDC를 읽지 못했습니다", e);
        }
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT);
        for (InventoryData.Effect e : data.effects()) {
            PotionEffectType type =
                    Key.parseable(e.type()) ? registry.get(Key.key(e.type())) : null;
            if (type != null) {
                player.addPotionEffect(
                        new PotionEffect(
                                type,
                                e.duration(),
                                e.amplifier(),
                                e.ambient(),
                                e.particles(),
                                e.icon()));
            }
        }
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = max == null ? 20.0 : max.getValue();
        double health = data.health() <= 0 ? maxHealth : Math.min(data.health(), maxHealth);
        player.setHealth(health);
        player.setFoodLevel(data.food());
        player.setSaturation(data.saturation());
        player.setExhaustion(data.exhaustion());
        player.setTotalExperience(data.totalExp());
        player.setLevel(data.level());
        if (data.exp() >= 0f && data.exp() <= 1f) {
            player.setExp(data.exp());
        }
        if (data.heldSlot() >= 0 && data.heldSlot() <= 8) {
            player.getInventory().setHeldItemSlot(data.heldSlot());
        }
        player.updateInventory();
    }

    /** 저장된 칸 수가 지금 크기와 다르면 맞춘다 (모자라면 빈 칸, 넘치면 자른다). */
    private static ItemStack[] fit(ItemStack[] items, int size) {
        ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < size; i++) {
            out[i] = i < items.length ? items[i] : ItemStack.empty();
        }
        return out;
    }

    // ------------------------------------------------------------------ 저장

    private void beforeSave(Player player, HarborPlayer hp, PlayerManager.SaveReason reason) {
        UUID uuid = player.getUniqueId();
        if (!enabled() || !synced.contains(uuid)) {
            return;
        }
        boolean leaving =
                reason == PlayerManager.SaveReason.QUIT
                        || reason == PlayerManager.SaveReason.HANDOFF
                        || reason == PlayerManager.SaveReason.SHUTDOWN;
        if (leaving) {
            // 커서·제작 칸·열린 창의 아이템을 인벤토리로 돌린 뒤 뜬다
            player.closeInventory();
        }
        InventoryData data = capture(player);
        hp.set(component, new Synced(data));
        if (leaving) {
            byte[] encoded = data.encode();
            int keep = keep();
            String why = reason.name();
            // 같은 큐에 넣어서 잠금을 넘기기 전 flushNow에 함께 쓰인다
            queue.enqueue(
                    prefix(uuid) + "inventory_history",
                    c -> InventoryRepository.addHistory(c, uuid, why, server, encoded, keep));
        }
    }

    static InventoryData capture(Player player) {
        List<InventoryData.Effect> effects = new ArrayList<>();
        for (PotionEffect e : player.getActivePotionEffects()) {
            if (e.isAmbient()) {
                continue; // 신호기·전달체 효과는 그 자리에서만 (HuskSync와 같음)
            }
            effects.add(
                    new InventoryData.Effect(
                            e.getType().key().asString(),
                            e.getDuration(),
                            e.getAmplifier(),
                            false,
                            e.hasParticles(),
                            e.hasIcon()));
        }
        return new InventoryData(
                ItemStack.serializeItemsAsBytes(nonNull(player.getInventory().getContents())),
                ItemStack.serializeItemsAsBytes(nonNull(player.getEnderChest().getContents())),
                serializePdc(player),
                player.getLevel(),
                player.getExp(),
                player.getTotalExperience(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getExhaustion(),
                player.getInventory().getHeldItemSlot(),
                effects);
    }

    private static byte[] serializePdc(Player player) {
        try {
            return player.getPersistentDataContainer().serializeToBytes();
        } catch (IOException e) {
            throw new IllegalStateException("PDC를 저장하지 못했습니다", e);
        }
    }

    private static ItemStack[] nonNull(@Nullable ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            out[i] = item == null ? ItemStack.empty() : item;
        }
        return out;
    }

    /** 사망 직전 인벤토리를 기록에 남긴다 (복구용, 지금 상태는 바꾸지 않는다). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        if (!enabled() || !synced.contains(uuid)) {
            return;
        }
        byte[] encoded = capture(player).encode();
        int keep = keep();
        var _ =
                db.asyncVoid(
                        c ->
                                InventoryRepository.addHistory(
                                        c, uuid, "DEATH", server, encoded, keep));
    }

    // ------------------------------------------------------------------ 관리 (InventoryCommand)

    /** 이 서버에서 동기화 중인지. */
    boolean isSynced(UUID uuid) {
        return synced.contains(uuid);
    }

    /** 접속 중인 플레이어에게 기록을 적용하고 바로 저장한다 (복구). 메인 스레드. */
    void restoreOnline(Player player, InventoryData data) {
        apply(player, data);
        synced.add(player.getUniqueId());
        players.get(player.getUniqueId()).ifPresent(hp -> hp.set(component, new Synced(data)));
        byte[] encoded = data.encode();
        UUID uuid = player.getUniqueId();
        int keep = keep();
        var _ = players.saveNow(uuid);
        var _ =
                db.asyncVoid(
                        c ->
                                InventoryRepository.addHistory(
                                        c, uuid, "RESTORE", server, encoded, keep));
    }

    /** 오프라인 플레이어의 지금 상태를 기록으로 바꾼다 (복구). */
    java.util.concurrent.CompletableFuture<Void> restoreOffline(UUID uuid, InventoryData data) {
        byte[] encoded = data.encode();
        int keep = keep();
        return db.asyncVoid(
                c -> {
                    InventoryRepository.save(c, uuid, server, encoded);
                    InventoryRepository.addHistory(c, uuid, "RESTORE", server, encoded, keep);
                });
    }

    java.util.concurrent.CompletableFuture<List<InventoryRepository.HistoryRow>> history(
            UUID uuid, int limit) {
        return db.async(c -> InventoryRepository.history(c, uuid, limit));
    }

    java.util.concurrent.CompletableFuture<java.util.Optional<InventoryData>> load(
            UUID uuid, @Nullable Long historyId) {
        return db.async(
                c ->
                        historyId == null
                                ? InventoryRepository.load(c, uuid)
                                : InventoryRepository.historyData(c, uuid, historyId));
    }

    static String time(java.time.Instant at) {
        return TIME.format(at);
    }

    // ------------------------------------------------------------------ 보기 창

    /** /harbor inventory view 창 (읽기 전용). */
    static final class ViewHolder implements InventoryHolder {
        private @Nullable Inventory inventory;

        @Override
        public Inventory getInventory() {
            Inventory inv = inventory;
            if (inv == null) {
                throw new IllegalStateException("창이 아직 없습니다");
            }
            return inv;
        }

        void set(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    /** 54칸: 1줄 = 투구·흉갑·각반·신발·왼손, 2~4줄 = 가방, 5줄 = 핫바. */
    Inventory view(InventoryData data, String name) {
        ViewHolder holder = new ViewHolder();
        Inventory inv =
                Bukkit.createInventory(
                        holder,
                        54,
                        messages.get("inventory.view_title", Placeholder.unparsed("player", name)));
        holder.set(inv);
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(data.contents());
        int[] armor = {39, 38, 37, 36, 40};
        for (int i = 0; i < armor.length; i++) {
            if (armor[i] < items.length) {
                inv.setItem(i, items[armor[i]]);
            }
        }
        for (int i = 9; i < 36 && i < items.length; i++) {
            inv.setItem(i, items[i]);
        }
        for (int i = 0; i < 9 && i < items.length; i++) {
            inv.setItem(36 + i, items[i]);
        }
        return inv;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onViewClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ViewHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onViewDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ViewHolder) {
            event.setCancelled(true);
        }
    }

    String serverId() {
        return server;
    }
}
