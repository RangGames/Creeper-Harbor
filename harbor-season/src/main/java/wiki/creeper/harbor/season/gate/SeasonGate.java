package wiki.creeper.harbor.season.gate;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.BundleContents;
import io.papermc.paper.datacomponent.item.ItemContainerContents;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.season.SeasonContentView;
import wiki.creeper.harbor.season.api.SeasonClock;
import wiki.creeper.harbor.season.api.event.PlayerSeasonEnteredEvent;
import wiki.creeper.harbor.season.content.ResetPlan;
import wiki.creeper.harbor.season.data.SeasonProfile;
import wiki.creeper.harbor.season.data.SeasonRepository;

/**
 * Season cleanup on login (docs 20 11.4 SeasonGate). Inventories live in player files, not the DB,
 * so they are not wiped all at once at the transition but on the first login in the new season.
 *
 * <ol>
 *   <li>Applies if the last cleaned season is lower than the current season
 *   <li>Scans inventory, ender chest and equipment slots, opening bundles and shulker boxes too,
 *       and keeps only items that should stay (Harbor items with season_reset: false etc.)
 *   <li>Resets vanilla experience, effects and health and moves the player to the starting point
 *   <li>Updates the season number and saves immediately, then PlayerSeasonEnteredEvent and the new
 *       season intro Dialog
 * </ol>
 *
 * Only a summary of removed item IDs and counts is logged.
 */
public final class SeasonGate implements Listener {

    private static final int MAX_DEPTH = 4;

    private final HarborApi api;
    private final SeasonContentView content;
    private final SeasonClock clock;
    private final PlayerComponent<SeasonProfile> component;
    private final Logger log;

    public SeasonGate(
            HarborApi api,
            SeasonContentView content,
            SeasonClock clock,
            PlayerComponent<SeasonProfile> component,
            Logger log) {
        this.api = api;
        this.content = content;
        this.clock = clock;
        this.component = component;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Optional<HarborPlayer> hp = api.players().get(player.getUniqueId());
        if (hp.isEmpty()) {
            return;
        }
        SeasonProfile profile = hp.get().get(component);
        int current = clock.current().value();
        if (profile.lastSeason() >= current) {
            if (!profile.stored()) {
                // New account: create the row right away so it becomes the baseline for the next
                // season's cleanup decision
                SeasonProfile stored = profile.markStored();
                hp.get().set(component, stored);
                save(player, stored);
            }
            return;
        }
        enter(player, hp.get(), profile, current);
    }

    private void enter(Player player, HarborPlayer hp, SeasonProfile profile, int current) {
        ResetPlan.Gate gate = content.plan().gate();
        Map<String, Integer> removed = new TreeMap<>();
        Inventory inventory = player.getInventory();
        inventory.setContents(filter(inventory.getContents(), gate, current, removed, 0));
        Inventory ender = player.getEnderChest();
        ender.setContents(filter(ender.getContents(), gate, current, removed, 0));
        if (gate.resetVanillaState()) {
            player.setLevel(0);
            player.setExp(0f);
            player.setTotalExperience(0);
            player.clearActivePotionEffects();
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            player.setHealth(max == null ? 20.0 : max.getValue());
            player.setFoodLevel(20);
            player.setSaturation(5f);
            player.setFireTicks(0);
        }
        var _ = player.teleportAsync(spawn(gate));

        SeasonProfile updated = profile.withLastSeason(current).markStored();
        hp.set(component, updated);
        // Save the cleaned inventory first, then write "cleaned for this season". In the reverse
        // order, a server crash in between
        // would bring back last season's items and experience without the cleanup running again. If
        // the inventory save fails,
        // the record is not written, so cleanup runs again on the next login
        var _ =
                api.players()
                        .saveNow(player.getUniqueId())
                        .thenRun(() -> save(player, updated))
                        .exceptionally(
                                e -> {
                                    log.error(
                                            "시즌 정리 후 저장 실패, 다음 접속 때 다시 정리합니다: {}",
                                            player.getName(),
                                            e);
                                    return null;
                                });
        int stacks = removed.values().stream().mapToInt(Integer::intValue).sum();
        log.info(
                "시즌 정리 {} ({}): 시즌 {} → {}, 지운 아이템 {}",
                player.getName(),
                player.getUniqueId(),
                profile.lastSeason(),
                current,
                removed.isEmpty() ? "없음" : removed);
        Bukkit.getPluginManager()
                .callEvent(
                        new PlayerSeasonEnteredEvent(
                                player, profile.lastSeason(), clock.current(), stacks));
        if (content.balance().gateNotice()) {
            List<Component> body = new ArrayList<>();
            body.add(
                    api.messages()
                            .get(
                                    "season.gate.body",
                                    Placeholder.unparsed("season", clock.displayName()),
                                    Placeholder.unparsed("weeks", String.valueOf(clock.weeks()))));
            if (stacks > 0) {
                body.add(
                        api.messages()
                                .get(
                                        "season.gate.removed",
                                        Placeholder.unparsed("count", String.valueOf(stacks))));
            }
            api.dialogs().notice(player, api.messages().get("season.gate.title"), body);
        }
    }

    /** Saves immediately (bypassing the write-behind queue, docs 20 11.4 step 4). */
    private void save(Player player, SeasonProfile profile) {
        var _ =
                api.database()
                        .asyncVoid(
                                c -> SeasonRepository.saveProfile(c, player.getUniqueId(), profile))
                        .exceptionally(
                                e -> {
                                    log.error("시즌 정보 저장 실패: {}", player.getName(), e);
                                    return null;
                                });
    }

    /** New array with only the items to keep (slots preserved). */
    private ItemStack[] filter(
            ItemStack[] items,
            ResetPlan.Gate gate,
            int current,
            Map<String, Integer> removed,
            int depth) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            ItemStack stack = items[i];
            out[i] =
                    stack == null || stack.isEmpty()
                            ? stack
                            : keepOrNull(stack, gate, current, removed, depth);
        }
        return out;
    }

    private @Nullable ItemStack keepOrNull(
            ItemStack stack,
            ResetPlan.Gate gate,
            int current,
            Map<String, Integer> removed,
            int depth) {
        Optional<HarborItem> item = api.items().read(stack);
        boolean keep;
        String key;
        if (item.isEmpty()) {
            keep = gate.keepVanilla();
            key = stack.getType().getKey().toString();
        } else {
            switch (item.get()) {
                case HarborItem.Plain plain -> {
                    Optional<ItemDef> def =
                            api.content().snapshot().get(ItemDef.TYPE, plain.id().id());
                    keep = def.isPresent() && !def.get().seasonReset();
                    key = plain.id().toString();
                }
                case HarborItem.Fish fish -> {
                    keep = gate.keepOldFish() || fish.season().value() >= current;
                    key = fish.species().toString();
                }
            }
        }
        if (!keep) {
            removed.merge(key, stack.getAmount(), Integer::sum);
            return null;
        }
        if (depth < MAX_DEPTH) {
            ItemContainerContents container = stack.getData(DataComponentTypes.CONTAINER);
            if (container != null) {
                List<ItemStack> inner = new ArrayList<>();
                for (ItemStack s : container.contents()) {
                    ItemStack kept =
                            s.isEmpty() ? s : keepOrNull(s, gate, current, removed, depth + 1);
                    inner.add(kept == null ? ItemStack.empty() : kept);
                }
                stack.setData(
                        DataComponentTypes.CONTAINER,
                        ItemContainerContents.containerContents(inner));
            }
            BundleContents bundle = stack.getData(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundle != null) {
                List<ItemStack> inner = new ArrayList<>();
                for (ItemStack s : bundle.contents()) {
                    ItemStack kept =
                            s.isEmpty() ? null : keepOrNull(s, gate, current, removed, depth + 1);
                    if (kept != null) {
                        inner.add(kept);
                    }
                }
                stack.setData(
                        DataComponentTypes.BUNDLE_CONTENTS, BundleContents.bundleContents(inner));
            }
        }
        return stack;
    }

    private static Location spawn(ResetPlan.Gate gate) {
        ResetPlan.Spawn spawn = gate.spawn();
        if (spawn != null) {
            World world = Bukkit.getWorld(spawn.world());
            if (world != null) {
                return new Location(
                        world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
            }
        }
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }
}
