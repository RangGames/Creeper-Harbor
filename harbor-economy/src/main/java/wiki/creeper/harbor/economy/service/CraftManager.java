package wiki.creeper.harbor.economy.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.db.SqlConsumer;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.ItemId;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerException;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.StatKey;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.economy.api.CraftService;
import wiki.creeper.harbor.economy.api.event.ItemCraftedEvent;
import wiki.creeper.harbor.economy.content.EconomyBalance;
import wiki.creeper.harbor.economy.content.RecipeDef;
import wiki.creeper.harbor.economy.content.StationDef;
import wiki.creeper.harbor.season.api.WeeklyLimitService;

/**
 * 제작 (docs 20 7.4 제작, '13. 제작 · 상점 가격' 3장). 스테이션을 누르면 그 장소의 레시피만 보여준다. 흐름: 재료를 인벤토리에서 꺼내 보관 → 한
 * 트랜잭션으로 Season WeeklyLimitService.tryConsume(키 craft:<레시피>, 주간 제한이 있을 때) + take(CRAFT_COST) + 재료로
 * 쓴 고유 어종의 장부 DESTROYED → 커밋 후 결과 지급(넘치면 우편함), 실패하면 재료 반환.
 *
 * <p>해금: job_tier·level(Core 플레이어), feature(스킬이 올리는 보정자), 플래그(visited/<지역>은 World, quest/<경로>는
 * Quest가 세움 — 추가 제안). 재료 절약: 생산 계열 '장인의 손'(craft_material_save)은 gear 재료를 올림(개수 × (1 − 절약)), '미끼
 * 장인'(bait_craft_bonus)은 bait 결과 +1/랭크. 골드 비용은 줄지 않는다.
 */
public final class CraftManager implements CraftService {

    private static final StatKey MATERIAL_SAVE = StatKey.of("craft_material_save");
    private static final StatKey BAIT_BONUS = StatKey.of("bait_craft_bonus");

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final MailManager mail;
    private final TradeGuard guard;
    private final StationManager stations;

    public CraftManager(
            HarborApi api,
            Tasks tasks,
            Logger log,
            MailManager mail,
            TradeGuard guard,
            StationManager stations) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.mail = mail;
        this.guard = guard;
        this.stations = stations;
    }

    private EconomyBalance balance() {
        return new EconomyBalance(
                api.content()
                        .snapshot()
                        .single(wiki.creeper.harbor.common.content.type.Balance.TYPE));
    }

    // ------------------------------------------------------------------ 화면

    @Override
    public boolean openStation(Player player, HarborId stationId) {
        Optional<StationDef> station = stations.get(stationId);
        if (station.isEmpty() || station.get().kind().craftStation().isEmpty()) {
            return false;
        }
        open(
                player,
                station.get().kind().craftStation().get(),
                Component.text(station.get().name()));
        return true;
    }

    @Override
    public boolean openKind(Player player, String kind) {
        Optional<RecipeDef.Station> station =
                Keyed.fromKey(RecipeDef.Station.class, kind.toLowerCase(Locale.ROOT));
        if (station.isEmpty()) {
            return false;
        }
        open(
                player,
                station.get(),
                api.messages().get("economy.craft.station." + station.get().key()));
        return true;
    }

    /** /제작: 근처 스테이션의 화면. 없으면 false. */
    public boolean openNearby(Player player) {
        Optional<StationDef> near =
                stations.nearest(player, null, balance().craftStationRadius())
                        .filter(s -> s.kind().craftStation().isPresent());
        return near.isPresent() && openStation(player, near.get().id());
    }

    void open(Player player, RecipeDef.Station station, Component title) {
        List<RecipeDef> recipes =
                api.content().snapshot().all(RecipeDef.TYPE).values().stream()
                        .filter(r -> r.station() == station)
                        .sorted(Comparator.comparing(RecipeDef::id))
                        .toList();
        if (recipes.isEmpty()) {
            api.messages().send(player, "economy.craft.none");
            return;
        }
        PagedMenu<RecipeDef> menu =
                new PagedMenu<>(
                        title,
                        6,
                        recipes,
                        recipe ->
                                new PagedMenu.Entry(
                                        icon(player, recipe), e -> start(player, recipe)),
                        named(Material.ARROW, "economy.craft.previous"),
                        named(Material.ARROW, "economy.craft.next"));
        menu.show(player, 0);
    }

    private ItemStack named(Material material, String key) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, api.messages().get(key));
        return stack;
    }

    private Optional<ItemDef> resultDef(RecipeDef recipe) {
        return api.content().snapshot().get(ItemDef.TYPE, recipe.result().id());
    }

    private ItemStack icon(Player player, RecipeDef recipe) {
        Optional<ItemDef> def = resultDef(recipe);
        if (def.isEmpty()) {
            return ItemStack.of(Material.BARRIER);
        }
        UUID uuid = player.getUniqueId();
        int amount = resultAmount(uuid, recipe);
        ItemStack stack = api.items().create(def.get(), Math.min(amount, 64));
        List<Component> lore =
                new ArrayList<>(
                        Optional.ofNullable(stack.getData(DataComponentTypes.LORE))
                                .map(ItemLore::lines)
                                .orElse(List.of()));
        lore.add(api.messages().get("economy.craft.lore.header"));
        List<CraftMath.Slot> slots = slots(player);
        for (CraftMath.Need need : needs(uuid, recipe)) {
            boolean have = CraftMath.allocate(List.of(need), slots, this::fish).isPresent();
            lore.add(
                    api.messages()
                            .get(
                                    have ? "economy.craft.lore.have" : "economy.craft.lore.missing",
                                    Placeholder.component(
                                            "name", ingredientName(need.ingredient())),
                                    Placeholder.unparsed("count", String.valueOf(need.count()))));
        }
        if (recipe.gold() > 0) {
            lore.add(
                    api.messages()
                            .get(
                                    "economy.craft.lore.gold",
                                    Placeholder.unparsed("gold", String.valueOf(recipe.gold()))));
        }
        if (recipe.weeklyLimit() > 0) {
            lore.add(
                    api.messages()
                            .get(
                                    "economy.craft.lore.weekly",
                                    Placeholder.unparsed(
                                            "limit", String.valueOf(recipe.weeklyLimit()))));
        }
        String locked = lockReason(player, recipe);
        if (locked != null) {
            lore.add(api.messages().get(locked, unlockTags(recipe)));
        }
        stack.setData(
                DataComponentTypes.LORE,
                ItemLore.lore(
                        lore.stream()
                                .map(
                                        c ->
                                                c.decorationIfAbsent(
                                                        TextDecoration.ITALIC,
                                                        TextDecoration.State.FALSE))
                                .toList()));
        return stack;
    }

    /** 재료 이름. 바닐라 재료는 번역 키라 클라이언트 언어(한국어)로 보인다. */
    private Component ingredientName(RecipeDef.Ingredient ingredient) {
        ContentSnapshot snapshot = api.content().snapshot();
        return switch (ingredient) {
            case RecipeDef.Item(var id, var _) ->
                    Component.text(
                            snapshot.get(ItemDef.TYPE, id.id())
                                    .map(ItemDef::name)
                                    .orElse(id.toString()));
            case RecipeDef.Vanilla(var key, var _) -> {
                Material material = Material.matchMaterial(key);
                yield material == null
                        ? Component.text(key)
                        : Component.translatable(material.translationKey());
            }
            case RecipeDef.FishMatch match -> {
                List<String> parts = new ArrayList<>();
                if (!match.fishAny().isEmpty()) {
                    parts.add(
                            String.join(
                                    "·",
                                    match.fishAny().stream()
                                            .map(
                                                    f ->
                                                            snapshot.get(FishDef.TYPE, f.id())
                                                                    .map(FishDef::name)
                                                                    .orElse(f.toString()))
                                            .toList()));
                }
                var region = match.region();
                if (region != null) {
                    parts.add(
                            api.messages().has("economy.craft.region." + region.id().name())
                                    ? plain("economy.craft.region." + region.id().name())
                                    : region.id().name());
                }
                var grade = match.gradeMin();
                if (grade != null) {
                    parts.add(plain("economy.craft.grade." + grade.key()));
                }
                yield Component.text(String.join(" ", parts) + " " + plain("economy.craft.fish"));
            }
        };
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(api.messages().get(key));
    }

    private net.kyori.adventure.text.minimessage.tag.resolver.TagResolver unlockTags(
            RecipeDef recipe) {
        RecipeDef.Unlock u = recipe.unlock();
        var visited = u.visited();
        var quest = u.quest();
        return net.kyori.adventure.text.minimessage.tag.resolver.TagResolver.resolver(
                Placeholder.unparsed("tier", String.valueOf(u.jobTier())),
                Placeholder.unparsed("level", String.valueOf(u.level())),
                Placeholder.unparsed("region", visited == null ? "" : visited.id().name()),
                Placeholder.unparsed("quest", quest == null ? "" : quest.id().name()));
    }

    // ------------------------------------------------------------------ 규칙

    private Optional<FishDef> fish(FishId id) {
        return api.content().snapshot().get(FishDef.TYPE, id.id());
    }

    private List<CraftMath.Need> needs(UUID uuid, RecipeDef recipe) {
        return CraftMath.needs(
                recipe, api.modifiers().value(uuid, MATERIAL_SAVE), balance().craftMaxSave());
    }

    private int resultAmount(UUID uuid, RecipeDef recipe) {
        return CraftMath.resultAmount(
                recipe.amount(), recipe.category(), api.modifiers().value(uuid, BAIT_BONUS));
    }

    /** 잠겨 있으면 안내 문구 키, 아니면 null. */
    @Nullable String lockReason(Player player, RecipeDef recipe) {
        RecipeDef.Unlock u = recipe.unlock();
        HarborPlayer hp = api.players().get(player);
        if (hp.jobTier().level() < u.jobTier()) {
            return "economy.craft.locked.tier";
        }
        if (hp.level() < u.level()) {
            return "economy.craft.locked.level";
        }
        String feature = u.feature();
        if (feature != null
                && api.modifiers().value(player.getUniqueId(), StatKey.of(feature)) < 1) {
            String key = "economy.craft.locked.feature." + feature;
            return api.messages().has(key) ? key : "economy.craft.locked.feature.default";
        }
        var visited = u.visited();
        if (visited != null
                && !api.flags().hasPlayer(player.getUniqueId(), RecipeDef.visitedFlag(visited))) {
            return "economy.craft.locked.visited";
        }
        var quest = u.quest();
        if (quest != null
                && !api.flags().hasPlayer(player.getUniqueId(), RecipeDef.questFlag(quest))) {
            return "economy.craft.locked.quest";
        }
        for (String flag : u.flags()) {
            if (!api.flags().hasPlayer(player.getUniqueId(), flag)) {
                return "economy.craft.locked.flag";
            }
        }
        return null;
    }

    private List<CraftMath.Slot> slots(Player player) {
        PlayerInventory inv = player.getInventory();
        List<CraftMath.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<HarborItem> item = api.items().read(stack);
            slots.add(
                    new CraftMath.Slot(
                            i,
                            item.orElse(null),
                            item.isPresent() ? null : stack.getType().getKey().asString(),
                            stack.getAmount()));
        }
        return slots;
    }

    // ------------------------------------------------------------------ 제작

    private void start(Player player, RecipeDef recipe) {
        String locked = lockReason(player, recipe);
        if (locked != null) {
            api.messages().send(player, locked, unlockTags(recipe));
            return;
        }
        UUID uuid = player.getUniqueId();
        Optional<Map<Integer, Integer>> plan =
                CraftMath.allocate(needs(uuid, recipe), slots(player), this::fish);
        if (plan.isEmpty()) {
            api.messages().send(player, "economy.craft.missing");
            return;
        }
        if (api.players().get(player).balance(Currency.GOLD) < recipe.gold()) {
            api.messages().send(player, "economy.craft.no_gold");
            return;
        }
        Optional<ItemDef> def = resultDef(recipe);
        if (def.isEmpty()) {
            return;
        }
        int amount = resultAmount(uuid, recipe);
        player.closeInventory();
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("economy.craft.title"),
                        List.of(
                                api.messages()
                                        .get(
                                                "economy.craft.body",
                                                Placeholder.unparsed("name", def.get().name()),
                                                Placeholder.unparsed(
                                                        "amount", String.valueOf(amount)),
                                                Placeholder.unparsed(
                                                        "gold", String.valueOf(recipe.gold())))),
                        api.messages().get("economy.craft.yes"),
                        api.messages().get("economy.craft.no"),
                        ok -> {
                            if (ok) {
                                craft(player, recipe, def.get());
                            }
                        });
    }

    private void craft(Player player, RecipeDef recipe, ItemDef result) {
        UUID uuid = player.getUniqueId();
        if (lockReason(player, recipe) != null) {
            return;
        }
        Optional<Map<Integer, Integer>> plan =
                CraftMath.allocate(needs(uuid, recipe), slots(player), this::fish);
        if (plan.isEmpty()) {
            api.messages().send(player, "economy.craft.missing");
            return;
        }
        Optional<TradeGuard.Ticket> ticket = guard.begin(uuid);
        if (ticket.isEmpty()) {
            api.messages().send(player, "economy.busy");
            return;
        }
        // 보관: 배정된 칸에서 꺼낸다
        PlayerInventory inv = player.getInventory();
        List<ItemStack> custody = new ArrayList<>();
        List<UUID> uids = new ArrayList<>();
        for (Map.Entry<Integer, Integer> take : plan.get().entrySet()) {
            ItemStack stack = inv.getItem(take.getKey());
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack taken = stack.clone();
            taken.setAmount(take.getValue());
            custody.add(taken);
            api.items()
                    .read(taken)
                    .filter(i -> i instanceof HarborItem.Fish)
                    .map(i -> ((HarborItem.Fish) i).uid())
                    .ifPresent(
                            uid -> {
                                if (uid != null) {
                                    uids.add(uid);
                                }
                            });
            int left = stack.getAmount() - take.getValue();
            if (left <= 0) {
                inv.setItem(take.getKey(), null);
            } else {
                stack.setAmount(left);
            }
        }
        int amount = resultAmount(uuid, recipe);
        AtomicReference<String> reason = new AtomicReference<>("failed");
        WeeklyLimitService limits =
                recipe.weeklyLimit() > 0
                        ? api.service(WeeklyLimitService.class).orElse(null)
                        : null;
        if (recipe.weeklyLimit() > 0 && limits == null) {
            mail.giveOrMail(uuid, custody, "craft_return", "craft_fail:" + UUID.randomUUID());
            ticket.get().done();
            api.messages().send(player, "economy.craft.failed.season");
            return;
        }
        String limitKey = WeeklyLimitService.craftKey(recipe.limitName());
        SqlConsumer extra =
                c -> {
                    if (limits != null
                            && !limits.tryConsume(c, uuid, limitKey, recipe.weeklyLimit())) {
                        reason.set("weekly");
                        throw new LedgerException(LedgerError.REJECTED, "주간 제작 한도");
                    }
                    for (UUID uid : uids) {
                        if (!FishLedger.transition(
                                c, uid, FishLedger.State.ACTIVE, FishLedger.State.DESTROYED)) {
                            reason.set("ledger");
                            throw new LedgerException(
                                    LedgerError.REJECTED, "장부 상태가 ACTIVE가 아닌 재료: " + uid);
                        }
                    }
                };
        String ref = "craft:" + UUID.randomUUID();
        List<LedgerEntry> entries =
                recipe.gold() > 0
                        ? List.of(
                                new LedgerEntry(
                                        uuid,
                                        Currency.GOLD,
                                        -recipe.gold(),
                                        LedgerReason.CRAFT_COST,
                                        ref))
                        : List.of();
        var _ =
                ticket.get()
                        .track(Tx.run(api, tasks, entries, extra))
                        .thenAccept(
                                r -> {
                                    LedgerError error = Tx.error(r);
                                    if (error != null) {
                                        mail.giveOrMail(uuid, custody, "craft_return", ref);
                                        if (player.isOnline()) {
                                            api.messages()
                                                    .send(
                                                            player,
                                                            error == LedgerError.INSUFFICIENT_FUNDS
                                                                    ? "economy.craft.no_gold"
                                                                    : "economy.craft.failed."
                                                                            + reason.get());
                                        }
                                        return;
                                    }
                                    List<ItemStack> results = new ArrayList<>();
                                    int left = amount;
                                    while (left > 0) {
                                        ItemStack one = api.items().create(result, 1);
                                        int n = Math.min(left, one.getMaxStackSize());
                                        results.add(api.items().create(result, n));
                                        left -= n;
                                    }
                                    mail.giveOrMail(uuid, results, "craft_overflow", ref);
                                    log.info(
                                            "제작 {} {} x{} {}G ({})",
                                            player.getName(),
                                            recipe.id(),
                                            amount,
                                            recipe.gold(),
                                            ref);
                                    Bukkit.getPluginManager()
                                            .callEvent(
                                                    new ItemCraftedEvent(
                                                            uuid,
                                                            recipe.id(),
                                                            recipe.result(),
                                                            amount,
                                                            recipe.gold()));
                                    if (player.isOnline()) {
                                        api.messages()
                                                .send(
                                                        player,
                                                        "economy.craft.done",
                                                        Placeholder.unparsed("name", result.name()),
                                                        Placeholder.unparsed(
                                                                "amount", String.valueOf(amount)));
                                    }
                                });
    }

    /** 수리비 기준: 이 아이템을 만드는 레시피의 골드 비용 (buy_price가 없는 장비, docs 20 7.4 수리). */
    OptionalLong recipeGold(ItemId item) {
        return api.content().snapshot().all(RecipeDef.TYPE).values().stream()
                .filter(r -> r.result().equals(item) && r.gold() > 0)
                .mapToLong(RecipeDef::gold)
                .min();
    }
}
