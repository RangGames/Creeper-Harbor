package wiki.creeper.harbor.fishing.food;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.fishing.Locs;
import wiki.creeper.harbor.fishing.content.Effect;
import wiki.creeper.harbor.fishing.content.FishingBalance;
import wiki.creeper.harbor.fishing.content.FoodBuffs;
import wiki.creeper.harbor.fishing.data.FishingData;
import wiki.creeper.harbor.fishing.data.FishingRepository;

/**
 * Food buffs (docs 20 0.1 "food use and modifier registration belong to Fishing"). When a food item
 * (type: food) is fully eaten, the effects from fishing/food.yml are applied as modifiers with the
 * source {@code food:<name>} and an expiry time. Only one applies; eating another food removes the
 * previous buff. Written to fishing_food the moment it is eaten and reapplied for the remaining
 * time after rejoining or moving servers. A feast dish (feast_share) also applies the same buff to
 * nearby party members (party is Core PartyService, implemented by Boss). Main thread only.
 */
public final class FoodManager implements Listener {

    private final HarborApi api;
    private final PlayerComponent<FishingData> component;
    private final Logger log;

    public FoodManager(HarborApi api, PlayerComponent<FishingData> component, Logger log) {
        this.api = api;
        this.component = component;
        this.log = log;
    }

    private FoodBuffs buffs() {
        return api.content().snapshot().single(FoodBuffs.TYPE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Optional<ItemId> item =
                api.items()
                        .read(event.getItem())
                        .filter(i -> i instanceof HarborItem.Plain)
                        .map(i -> ((HarborItem.Plain) i).id());
        if (item.isEmpty()) {
            return;
        }
        boolean food =
                api.content()
                        .snapshot()
                        .get(ItemDef.TYPE, item.get().id())
                        .map(d -> d.type() == ItemDef.Type.FOOD)
                        .orElse(false);
        if (!food) {
            return;
        }
        Optional<FoodBuffs.Buff> buff = buffs().of(item.get());
        if (buff.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        apply(player, buff.get(), stat(player.getUniqueId(), "food_buff_duration"), false);
        if (buff.get().shareable() && stat(player.getUniqueId(), "feast_share") > 0) {
            share(player, buff.get());
        }
    }

    /**
     * Feast dish: the same buff for nearby party members in the same world (duration based on the
     * eater).
     */
    private void share(Player eater, FoodBuffs.Buff buff) {
        double radius =
                new FishingBalance(api.content().snapshot().single(Balance.TYPE)).feastRadius();
        double bonus = stat(eater.getUniqueId(), "food_buff_duration");
        Location at = Locs.of(eater);
        List<UUID> members =
                api.parties().map(p -> p.membersOf(eater.getUniqueId())).orElse(List.of());
        for (UUID member : members) {
            if (member.equals(eater.getUniqueId())) {
                continue;
            }
            Player other = Bukkit.getPlayer(member);
            if (other == null
                    || !other.getWorld().equals(eater.getWorld())
                    || Locs.of(other).distanceSquared(at) > radius * radius) {
                continue;
            }
            apply(other, buff, bonus, true);
        }
    }

    /** Applies the buff (removes the previous one). */
    public void apply(Player player, FoodBuffs.Buff buff, double durationBonus, boolean shared) {
        UUID uuid = player.getUniqueId();
        HarborPlayer hp = api.players().get(player);
        FishingData data = hp.get(component);
        Instant now = Instant.now();
        FoodRule.Replacement r = FoodRule.replace(data.food(), buff, now, durationBonus);
        String remove = r.removeSource();
        if (remove != null) {
            api.modifiers().removeSource(uuid, remove);
        }
        register(uuid, buff, r.next());
        hp.set(component, data.withFood(r.next()));
        // The buff goes straight to the DB, so also save the inventory without the eaten food soon
        // (if the server dies, the food comes back and the buff remains)
        api.players().saveSoon(uuid);
        var _ =
                api.database()
                        .asyncVoid(c -> FishingRepository.saveFood(c, uuid, r.next()))
                        .exceptionally(
                                e -> {
                                    log.warn("요리 버프 저장 실패 ({}): {}", uuid, String.valueOf(e));
                                    return null;
                                });
        long minutes = Math.max(1, Duration.between(now, r.next().expiresAt()).toMinutes());
        String name = itemName(buff.item());
        api.messages()
                .send(
                        player,
                        shared
                                ? "fishing.food.shared"
                                : remove != null ? "fishing.food.replaced" : "fishing.food.applied",
                        Placeholder.unparsed("name", name),
                        Placeholder.unparsed("minutes", String.valueOf(minutes)));
    }

    private void register(UUID uuid, FoodBuffs.Buff buff, ActiveFood active) {
        String source = active.source();
        for (Effect effect : buff.effects()) {
            api.modifiers()
                    .add(
                            uuid,
                            new Modifier(
                                    effect.stat(),
                                    effect.op(),
                                    effect.value(),
                                    source,
                                    active.expiresAt()));
        }
    }

    /** Join (including server moves): reapplies the buff if time remains. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Optional<HarborPlayer> hp = api.players().get(player.getUniqueId());
        if (hp.isEmpty()) {
            return;
        }
        FishingData data = hp.get().get(component);
        ActiveFood food = data.food();
        if (food == null) {
            return;
        }
        if (!food.isActive(Instant.now())) {
            hp.get().set(component, data.withFood(null));
            return;
        }
        buffs().of(food.item()).ifPresent(buff -> register(player.getUniqueId(), buff, food));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Optional<HarborPlayer> hp = api.players().get(event.getPlayer().getUniqueId());
        hp.map(h -> h.get(component).food())
                .ifPresent(
                        food ->
                                api.modifiers()
                                        .removeSource(
                                                event.getPlayer().getUniqueId(), food.source()));
    }

    /** The currently active food buff (admin command). */
    public @Nullable ActiveFood active(Player player) {
        ActiveFood food = api.players().get(player).get(component).food();
        return food != null && food.isActive(Instant.now()) ? food : null;
    }

    private String itemName(ItemId item) {
        return api.content()
                .snapshot()
                .get(ItemDef.TYPE, item.id())
                .map(ItemDef::name)
                .orElse(item.toString());
    }

    private double stat(UUID uuid, String key) {
        return api.modifiers().value(uuid, StatKey.of(key));
    }
}
