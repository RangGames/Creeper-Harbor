package wiki.creeper.harbor.npc.affinity;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.PlayerComponent;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.AffinityChange;
import wiki.creeper.harbor.npc.api.AffinityLevel;
import wiki.creeper.harbor.npc.api.AffinityService;
import wiki.creeper.harbor.npc.api.AffinitySource;
import wiki.creeper.harbor.npc.api.GiftReaction;
import wiki.creeper.harbor.npc.api.event.AffinityChangedEvent;
import wiki.creeper.harbor.npc.api.event.AffinityLevelUpEvent;
import wiki.creeper.harbor.npc.api.event.GiftGivenEvent;
import wiki.creeper.harbor.npc.content.NpcBalance;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.npc.data.AffinityState;
import wiki.creeper.harbor.npc.data.NpcData;
import wiki.creeper.harbor.npc.data.NpcRepository;
import wiki.creeper.harbor.npc.entity.NpcManager;
import wiki.creeper.harbor.npc.logic.AffinityMath;

/**
 * 호감도·선물 (docs 20 5.4). 값은 PlayerComponent<NpcData>에 두고 Core가 지연 쓰기 큐로 저장한다 (재화가 아님). 메인 스레드 전용.
 */
public final class AffinityManager implements AffinityService {

    private final HarborApi api;
    private final Tasks tasks;
    private final NpcManager npcs;
    private final Logger log;
    private final PlayerComponent<NpcData> component;

    public AffinityManager(HarborApi api, Tasks tasks, NpcManager npcs, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.npcs = npcs;
        this.log = log;
        this.component =
                new PlayerComponent<>(
                        "npc",
                        (c, uuid, season) ->
                                NpcRepository.load(
                                        c,
                                        uuid,
                                        season,
                                        api.time().gameDay(),
                                        balance().recentSize()),
                        (c, uuid, season, data) ->
                                NpcRepository.saveAffinity(c, uuid, season, data.affinity()));
    }

    public PlayerComponent<NpcData> component() {
        return component;
    }

    NpcBalance balance() {
        return new NpcBalance(api.content().snapshot().single(Balance.TYPE));
    }

    private Optional<NpcData> data(UUID player) {
        return api.players().get(player).map(hp -> hp.get(component));
    }

    // ------------------------------------------------------------------ 조회

    @Override
    public int points(UUID player, NpcId npc) {
        return data(player).map(d -> d.affinity(npc).points()).orElse(0);
    }

    @Override
    public int level(UUID player, NpcId npc) {
        return levelInfo(player, npc).level();
    }

    @Override
    public AffinityLevel levelInfo(UUID player, NpcId npc) {
        return AffinityMath.levelFor(points(player, npc), levels(npc));
    }

    @Override
    public List<AffinityLevel> levels(NpcId npc) {
        return npcs.def(npc)
                .map(NpcDef::affinityLevels)
                .filter(l -> !l.isEmpty())
                .orElseGet(() -> balance().affinityLevels());
    }

    @Override
    public boolean hasUnlock(UUID player, NpcId npc, String unlock) {
        int points = points(player, npc);
        return levels(npc).stream()
                .anyMatch(l -> l.points() <= points && l.unlocks().contains(unlock));
    }

    @Override
    public int discountPercent(UUID player, NpcId npc) {
        return levelInfo(player, npc).discountPercent();
    }

    // ------------------------------------------------------------------ 변경

    @Override
    public AffinityChange add(UUID player, NpcId npc, int delta, AffinitySource source) {
        Optional<? extends HarborPlayer> hp = api.players().get(player);
        if (hp.isEmpty() || npcs.def(npc).isEmpty()) {
            int p = points(player, npc);
            return AffinityChange.rejected(p, level(player, npc));
        }
        NpcData data = hp.get().get(component);
        AffinityState state = data.affinity(npc);
        return apply(
                hp.get(),
                data,
                npc,
                state,
                AffinityMath.apply(state.points(), delta) - state.points(),
                source);
    }

    /** 상태를 바꾸고 이벤트를 낸다. state에는 선물·인사 날짜 갱신이 이미 들어 있을 수 있다. */
    private AffinityChange apply(
            HarborPlayer hp,
            NpcData data,
            NpcId npc,
            AffinityState state,
            int delta,
            AffinitySource source) {
        List<AffinityLevel> levels = levels(npc);
        int oldPoints = data.affinity(npc).points();
        int newPoints = AffinityMath.apply(state.points(), delta);
        int oldLevel = AffinityMath.levelFor(oldPoints, levels).level();
        int newLevel = AffinityMath.levelFor(newPoints, levels).level();
        hp.set(component, data.withAffinity(npc, state.withPoints(newPoints)));
        if (newPoints != oldPoints) {
            Bukkit.getPluginManager()
                    .callEvent(
                            new AffinityChangedEvent(hp.uuid(), npc, oldPoints, newPoints, source));
        }
        if (newLevel > oldLevel) {
            Bukkit.getPluginManager()
                    .callEvent(new AffinityLevelUpEvent(hp.uuid(), npc, oldLevel, newLevel));
            Player player = Bukkit.getPlayer(hp.uuid());
            if (player != null) {
                api.messages()
                        .send(
                                player,
                                "npc.affinity.level_up",
                                Placeholder.component("npc", npcs.displayName(player, npc)),
                                Placeholder.unparsed("level", levels.get(newLevel).name()));
            }
        }
        return new AffinityChange(true, oldPoints, newPoints, oldLevel, newLevel);
    }

    /** 하루 첫 대화 (docs 20 5.4 3단계, 제안값 +2). */
    public void greet(Player player, NpcDef def) {
        if (!def.greet()) {
            return;
        }
        HarborPlayer hp = api.players().get(player);
        NpcData data = hp.get(component);
        LocalDate today = api.time().gameDay();
        AffinityState state = data.affinity(def.id());
        if (state.greetedOn(today)) {
            return;
        }
        apply(
                hp,
                data,
                def.id(),
                state.greeted(today),
                balance().greetPoints(),
                AffinitySource.GREETING);
    }

    // ------------------------------------------------------------------ 선물

    /** 대화 액션 gift:open: 손에 든 아이템 1개를 선물한다 (docs 20 5.4 선물 1~4단계). */
    public ActionResult openGift(Player player, NpcId npc) {
        Optional<NpcDef> def = npcs.def(npc);
        if (def.isEmpty()) {
            return ActionResult.CLOSE;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() == Material.AIR || held.getAmount() <= 0) {
            return ActionResult.failMessage("npc.gift.empty_hand");
        }
        HarborPlayer hp = api.players().get(player);
        LocalDate today = api.time().gameDay();
        NpcBalance balance = balance();
        if (hp.get(component).affinity(npc).giftsOn(today) >= balance.giftsPerDay()) {
            return ActionResult.failMessage("npc.gift.already");
        }
        Optional<HarborItem> item = api.items().read(held);
        HarborId id = item.map(AffinityManager::idOf).orElse(null);
        int stars =
                item.filter(HarborItem.Fish.class::isInstance)
                        .map(i -> ((HarborItem.Fish) i).quality().stars())
                        .orElse(0);
        int base =
                id == null
                        ? balance.giftDefault()
                        : def.get().gifts().getOrDefault(id, balance.giftDefault());
        UUID uid =
                item.filter(HarborItem.Fish.class::isInstance)
                        .map(i -> ((HarborItem.Fish) i).uid())
                        .orElse(null);
        if (uid != null) {
            // 고유 ID 아이템은 확인 Dialog를 거친다
            api.dialogs()
                    .confirm(
                            player,
                            npcs.displayName(player, npc),
                            List.of(
                                    api.messages()
                                            .get(
                                                    "npc.gift.confirm_unique",
                                                    Placeholder.component(
                                                            "item", held.effectiveName()))),
                            api.messages().get("npc.gift.confirm_yes"),
                            api.messages().get("npc.gift.confirm_no"),
                            yes -> {
                                if (yes) {
                                    give(player, def.get(), id, stars, base, uid);
                                }
                            });
            return ActionResult.HANDOFF;
        }
        give(player, def.get(), id, stars, base, null);
        return ActionResult.HANDOFF;
    }

    private static @Nullable HarborId idOf(HarborItem item) {
        return switch (item) {
            case HarborItem.Plain p -> p.id().id();
            case HarborItem.Fish f -> f.species().id();
        };
    }

    /** 4단계: 메인 스레드에서 아이템을 즉시 빼고 호감도를 메모리에 반영한 뒤 반응 대사를 띄운다. */
    private void give(
            Player player,
            NpcDef def,
            @Nullable HarborId id,
            int stars,
            int base,
            @Nullable UUID uid) {
        if (!player.isOnline()) {
            return;
        }
        HarborPlayer hp = api.players().get(player);
        LocalDate today = api.time().gameDay();
        NpcBalance balance = balance();
        NpcData data = hp.get(component);
        AffinityState state = data.affinity(def.id());
        if (state.giftsOn(today) >= balance.giftsPerDay()) {
            api.messages().send(player, "npc.gift.already");
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        Optional<HarborItem> now = api.items().read(held);
        HarborId nowId = now.map(AffinityManager::idOf).orElse(null);
        boolean same =
                held.getType() != Material.AIR
                        && java.util.Objects.equals(id, nowId)
                        && (uid == null
                                || now.filter(HarborItem.Fish.class::isInstance)
                                        .map(i -> uid.equals(((HarborItem.Fish) i).uid()))
                                        .orElse(false));
        if (!same) {
            api.messages().send(player, "npc.gift.changed");
            return;
        }
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItem(EquipmentSlot.HAND, held.getAmount() <= 0 ? null : held);
        if (uid != null) {
            // 장부는 바로 GIFTED가 되므로 선물한 아이템이 빠진 인벤토리도 곧 저장한다. 서버가 죽으면 GIFTED 상태의 아이템이 돌아와
            // 복사본으로 판정된다 (판매·경매 불가)
            api.players().saveSoon(player.getUniqueId());
            // 장부 상태를 바꾼 뒤 성공했을 때만 호감도를 준다. 실패(이미 GIFTED·SOLD 등)면 복사된 아이템이다: 예전에는 기록만 하고
            // 호감도를 그대로 줘서 복사한 5성 물고기로 매일 최대 호감도를 받을 수 있었다
            var _ =
                    api.database()
                            .async(
                                    c ->
                                            FishLedger.transition(
                                                    c,
                                                    uid,
                                                    FishLedger.State.ACTIVE,
                                                    FishLedger.State.GIFTED))
                            .handleAsync(
                                    (ok, ex) -> {
                                        if (ex != null || !Boolean.TRUE.equals(ok)) {
                                            log.warn(
                                                    "선물한 고유 ID 아이템 {}의 장부 상태가 ACTIVE가 아닙니다 (복사 의심, {})",
                                                    uid,
                                                    player.getName());
                                            if (player.isOnline()) {
                                                api.messages().send(player, "npc.gift.invalid");
                                            }
                                        } else if (player.isOnline()) {
                                            reward(player, def, id, stars, base);
                                        }
                                        return null;
                                    },
                                    tasks.main());
            return;
        }
        reward(player, def, id, stars, base);
    }

    /** 선물 반응: 호감도 반영·이벤트·대사 (메인 스레드). 지금 데이터를 다시 읽는다 (고유 ID 선물은 장부 확인 뒤에 온다). */
    private void reward(Player player, NpcDef def, @Nullable HarborId id, int stars, int base) {
        HarborPlayer hp = api.players().get(player);
        LocalDate today = api.time().gameDay();
        NpcBalance balance = balance();
        NpcData data = hp.get(component);
        AffinityState state = data.affinity(def.id());
        int points = AffinityMath.giftPoints(base, stars, balance.giftQualityBonus());
        GiftReaction reaction =
                AffinityMath.reaction(base, balance.reactionLove(), balance.reactionLike());
        apply(hp, data, def.id(), state.gifted(today), points, AffinitySource.GIFT);
        Bukkit.getPluginManager()
                .callEvent(new GiftGivenEvent(player, def.id(), id, reaction, points));
        String line = def.giftLines().getOrDefault(reaction, "npc.gift.reaction." + reaction.key());
        api.dialogs()
                .notice(
                        player,
                        npcs.displayName(player, def.id()),
                        List.of(
                                api.messages()
                                        .get(
                                                line,
                                                Placeholder.unparsed("player", player.getName())),
                                api.messages()
                                        .get(
                                                "npc.gift.result",
                                                Placeholder.unparsed(
                                                        "points",
                                                        (points >= 0 ? "+" : "") + points))));
    }

    /** /harbor npc affinity set. */
    public AffinityChange set(UUID player, NpcId npc, int value) {
        return add(player, npc, Math.max(0, value) - points(player, npc), AffinitySource.ADMIN);
    }
}
