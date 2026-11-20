package wiki.creeper.harbor.fishing.trap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.fishing.api.event.TrapHarvestEvent;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.session.FishingManager;

/**
 * 통발 GUI 동작: 수확(트랜잭션 커밋 후 아이템 생성, trap 출처 표시), 미끼 넣기·반환, 철거. 수확물 아이템 생성은 자동 회수도 같이 쓴다. 메인 스레드 전용.
 */
final class TrapHarvest {

    private static final String SOURCE_TRAP = "trap";

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final FishingManager fishing;
    private final TrapManager traps;
    private final TrapBlocks blocks;
    private final TrapMenu menu;
    private final NamespacedKey sourceKey;

    TrapHarvest(
            Plugin plugin,
            HarborApi api,
            Tasks tasks,
            Logger log,
            FishingManager fishing,
            TrapManager traps,
            TrapBlocks blocks,
            TrapMenu menu) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.fishing = fishing;
        this.traps = traps;
        this.blocks = blocks;
        this.menu = menu;
        this.sourceKey = new NamespacedKey(plugin, "source");
    }

    boolean isTrapCatch(ItemStack stack) {
        return SOURCE_TRAP.equals(
                stack.getPersistentDataContainer().get(sourceKey, PersistentDataType.STRING));
    }

    /** 항목을 수확한다 (트랜잭션 커밋 후 아이템 생성). 가방 빈칸 수만큼만 꺼낸다. */
    void harvest(Player player, TrapRow row, List<TrapCatch> entries) {
        int free = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.isEmpty()) {
                free++;
            }
        }
        if (free == 0) {
            api.messages().send(player, "fishing.trap.full");
            return;
        }
        Set<String> uids = new HashSet<>();
        for (TrapCatch e : entries) {
            if (uids.size() >= free) {
                break;
            }
            uids.add(e.uid());
        }
        UUID uuid = player.getUniqueId();
        Instant now = Instant.now();
        var _ =
                traps.track(
                                uuid,
                                api.database()
                                        .async(
                                                c ->
                                                        TrapRepository.take(
                                                                c, row.id(), uuid, uids, now)))
                        .whenCompleteAsync(
                                (taken, error) -> {
                                    if (error != null) {
                                        log.warn(
                                                "통발 수확 실패 ({}): {}",
                                                row.id(),
                                                String.valueOf(error));
                                        api.messages().send(player, "fishing.trap.failed");
                                        return;
                                    }
                                    List<HarborItem.Fish> given = new ArrayList<>();
                                    for (TrapCatch entry : taken) {
                                        ItemStack stack = createCatch(player, entry);
                                        if (stack == null) {
                                            continue;
                                        }
                                        api.items()
                                                .read(stack)
                                                .filter(i -> i instanceof HarborItem.Fish)
                                                .ifPresent(i -> given.add((HarborItem.Fish) i));
                                        fishing.give(player, stack);
                                    }
                                    if (!given.isEmpty()) {
                                        api.messages()
                                                .send(
                                                        player,
                                                        "fishing.trap.harvested",
                                                        Placeholder.unparsed(
                                                                "count",
                                                                String.valueOf(given.size())));
                                        Bukkit.getPluginManager()
                                                .callEvent(
                                                        new TrapHarvestEvent(
                                                                player,
                                                                row.id(),
                                                                row.region(),
                                                                given));
                                    }
                                    reopen(player, row.id());
                                },
                                tasks.main());
    }

    /** 통발 항목 → 어종 아이템 (trap 출처 표시). 콘텐츠에서 사라진 어종이면 null. */
    @Nullable ItemStack createCatch(Player owner, TrapCatch entry) {
        return createCatch(api.players().get(owner).ref(), entry);
    }

    @Nullable ItemStack createCatch(
            wiki.creeper.harbor.common.model.PlayerRef owner, TrapCatch entry) {
        Optional<FishDef> def;
        try {
            def = api.content().snapshot().get(FishDef.TYPE, FishId.parse(entry.fish()).id());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (def.isEmpty()) {
            return null;
        }
        ItemStack stack =
                api.items()
                        .createFish(
                                def.get(),
                                entry.size(),
                                Quality.of(Math.clamp(entry.quality(), Quality.MIN, Quality.MAX)),
                                owner,
                                Instant.ofEpochMilli(entry.caughtAt()));
        stack.editPersistentDataContainer(
                pdc -> pdc.set(sourceKey, PersistentDataType.STRING, SOURCE_TRAP));
        return stack;
    }

    void reopen(Player player, long id) {
        if (!player.isOnline()) {
            return;
        }
        var _ =
                api.database()
                        .async(c -> TrapRepository.byId(c, id, false))
                        .whenCompleteAsync(
                                (row, error) -> {
                                    if (error == null && row.isPresent()) {
                                        menu.open(player, row.get());
                                    } else {
                                        player.closeInventory();
                                    }
                                },
                                tasks.main());
    }

    /** 가방의 미끼를 통발에 넣는다 (사용 횟수 미끼·전설의 미끼 제외). */
    void addBait(Player player, TrapRow row) {
        FishingBalance b = traps.balance();
        int room = b.trapBaitCapacity() - row.bait();
        if (room <= 0) {
            api.messages().send(player, "fishing.trap.bait_full");
            return;
        }
        int taken = 0;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (ItemStack stack : contents) {
            if (taken >= room || stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<ItemDef> def = traps.fishingItem(stack);
            if (def.isEmpty()
                    || def.get().type() != ItemDef.Type.BAIT
                    || def.get().stats().getOrDefault("legend_only", 0.0) > 0
                    || def.get().stats().getOrDefault("uses", 0.0) > 1) {
                continue;
            }
            int n = Math.min(room - taken, stack.getAmount());
            stack.setAmount(stack.getAmount() - n);
            taken += n;
        }
        if (taken == 0) {
            api.messages().send(player, "fishing.trap.no_bait");
            return;
        }
        int amount = taken;
        UUID uuid = player.getUniqueId();
        var _ =
                traps.track(
                                uuid,
                                api.database()
                                        .async(
                                                c ->
                                                        TrapRepository.addBait(
                                                                c,
                                                                row.id(),
                                                                uuid,
                                                                amount,
                                                                b.trapBaitCapacity())))
                        .whenCompleteAsync(
                                (ok, error) -> {
                                    if (error != null || !Boolean.TRUE.equals(ok)) {
                                        refundBait(player, amount);
                                        api.messages().send(player, "fishing.trap.failed");
                                        return;
                                    }
                                    api.messages()
                                            .send(
                                                    player,
                                                    "fishing.trap.bait_added",
                                                    Placeholder.unparsed(
                                                            "count", String.valueOf(amount)));
                                    reopen(player, row.id());
                                },
                                tasks.main());
    }

    /** 미끼 반환 (어떤 미끼였는지 기록하지 않으므로 기본 미끼 fishing.trap.refund_bait_item, 제안값). */
    private void refundBait(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        refundBaitItems(amount).forEach(s -> fishing.give(player, s));
    }

    List<ItemStack> refundBaitItems(int amount) {
        String id =
                api.content()
                        .snapshot()
                        .single(Balance.TYPE)
                        .string("fishing.trap.refund_bait_item")
                        .orElse("harbor:item/bait/earthworm");
        List<ItemStack> result = new ArrayList<>();
        try {
            api.content()
                    .snapshot()
                    .get(ItemDef.TYPE, ItemId.parse(id).id())
                    .ifPresent(
                            def -> {
                                int left = amount;
                                while (left > 0) {
                                    int n = Math.min(64, left);
                                    result.add(api.items().create(def, n));
                                    left -= n;
                                }
                            });
        } catch (IllegalArgumentException e) {
            log.warn("fishing.trap.refund_bait_item 형식이 틀렸습니다: {}", id);
        }
        return result;
    }

    /** 철거: 내용물이 비었을 때만. 통발 아이템과 남은 미끼를 돌려준다. */
    void remove(Player player, TrapRow row) {
        if (!row.contents().isEmpty()) {
            api.messages().send(player, "fishing.trap.not_empty");
            return;
        }
        UUID uuid = player.getUniqueId();
        var _ =
                traps.track(
                                uuid,
                                api.database()
                                        .async(c -> TrapRepository.deleteEmpty(c, row.id(), uuid)))
                        .whenCompleteAsync(
                                (ok, error) -> {
                                    if (error != null || !Boolean.TRUE.equals(ok)) {
                                        api.messages().send(player, "fishing.trap.failed");
                                        return;
                                    }
                                    player.closeInventory();
                                    blocks.demolish(
                                            row.world(), row.x(), row.y(), row.z(), row.id());
                                    api.content()
                                            .snapshot()
                                            .get(ItemDef.TYPE, row.item().id())
                                            .ifPresent(
                                                    def ->
                                                            fishing.give(
                                                                    player,
                                                                    api.items().create(def, 1)));
                                    refundBait(player, row.bait());
                                    api.messages().send(player, "fishing.trap.removed");
                                },
                                tasks.main());
    }
}
