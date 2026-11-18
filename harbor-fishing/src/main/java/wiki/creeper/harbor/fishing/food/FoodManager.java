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
 * 요리 버프 (docs 20 0.1 "요리 사용과 보정자 등록은 Fishing"). 요리(type: food) 아이템을 다 먹으면 fishing/food.yml의 효과를 출처
 * {@code food:<이름>}, 만료 시각이 있는 보정자로 건다. 1종만 적용되어 다른 요리를 먹으면 앞 버프를 지운다. 먹는 순간 fishing_food에 즉시 쓰고,
 * 재접속· 서버 이동 뒤 남은 시간만큼 다시 건다. 연회 요리(feast_share)면 근처 파티원에게도 같은 버프를 건다 (파티는 Core PartyService, 구현
 * Boss). 메인 스레드 전용.
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

    /** 연회 요리: 같은 월드의 가까운 파티원에게 같은 버프 (지속 시간은 먹은 사람 기준). */
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

    /** 버프를 건다 (앞 버프는 지운다). */
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

    /** 접속 (서버 이동 포함): 남은 시간이 있으면 다시 건다. */
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

    /** 지금 걸린 요리 버프 (관리 명령). */
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
