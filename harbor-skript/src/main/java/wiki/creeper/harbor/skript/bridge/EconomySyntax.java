package wiki.creeper.harbor.skript.bridge;

import ch.njol.skript.lang.Expression;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.Harbor;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.economy.api.AuctionListingInfo;
import wiki.creeper.harbor.economy.api.AuctionService;
import wiki.creeper.harbor.economy.api.CraftService;
import wiki.creeper.harbor.economy.api.PriceService;
import wiki.creeper.harbor.economy.api.RepairService;
import wiki.creeper.harbor.economy.api.SaleService;
import wiki.creeper.harbor.economy.api.ShopService;
import wiki.creeper.harbor.economy.api.TradeResult;
import wiki.creeper.harbor.economy.api.event.AuctionListedEvent;
import wiki.creeper.harbor.economy.api.event.AuctionSoldEvent;
import wiki.creeper.harbor.economy.api.event.FishSoldEvent;
import wiki.creeper.harbor.economy.api.event.ItemCraftedEvent;
import wiki.creeper.harbor.economy.api.event.MarketUpdatedEvent;
import wiki.creeper.harbor.economy.api.event.ShopPurchaseEvent;
import wiki.creeper.harbor.economy.api.event.TradeFlaggedEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Args;
import wiki.creeper.harbor.skript.lambda.Ctx;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;
import wiki.creeper.harbor.skript.lambda.Spec;

/**
 * When Harbor-Economy is enabled: market price factor (now and the next hour's forecast), fish sell
 * quotes, opening shop and crafting screens, repair cost quotes, auction house reads, unclaimed
 * mail count, and all economy api events.
 *
 * <p>Auction and mail reads are DB lookups, so they are served from {@link AsyncCache} (eventually
 * consistent: no value on the first read, refreshed every balance.yml {@code
 * skript.cache_seconds}). Selling, shop buying, auction listing and buying, crafting and repair for
 * a player go through the Economy api flows (SaleService, ShopService, AuctionService,
 * CraftService, RepairService) and wait for the transaction; other gold changes stay on {@code
 * harbor gold} (ledger SCRIPT_*).
 */
public final class EconomySyntax {

    private EconomySyntax() {}

    private static <T> Optional<T> service(Class<T> type) {
        return Harbor.api().service(type);
    }

    private static Optional<PriceService> prices() {
        return service(PriceService.class);
    }

    private static Optional<FishId> fish(String raw) {
        return Ids.parse(raw, FishId.PREFIX).map(FishId::new);
    }

    /** Auction listings per category ("" = all). */
    private static final AsyncCache<String, List<AuctionListingInfo>> LISTINGS =
            new AsyncCache<>(
                    category ->
                            service(AuctionService.class)
                                    .orElseThrow()
                                    .active(category.isEmpty() ? null : category, auctionLimit()));

    private static final AsyncCache<String, OptionalLong> LOWEST =
            new AsyncCache<>(item -> service(AuctionService.class).orElseThrow().lowestPrice(item));

    private static final AsyncCache<UUID, Integer> ACTIVE_COUNT =
            new AsyncCache<>(
                    seller -> service(AuctionService.class).orElseThrow().activeCount(seller));

    private static final AsyncCache<UUID, Integer> UNCLAIMED =
            new AsyncCache<>(p -> Harbor.api().mail().orElseThrow().unclaimed(p));

    private static int auctionLimit() {
        Balance b = Harbor.api().content().snapshot().single(Balance.TYPE);
        return (int) Math.max(1, Math.round(b.number("skript.auction_limit", 100)));
    }

    public static void register(Registrar r) {
        market(r);
        screens(r);
        trades(r);
        auction(r);
        events(r);
    }

    // ------------------------------------------------------------------ market and quotes

    private static void market(Registrar r) {
        r.number(
                Spec.single("harbor market coefficient"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 0).flatMap(EconomySyntax::fish);
                    Optional<PriceService> p = prices();
                    return fish.isPresent() && p.isPresent()
                            ? List.of(p.get().coefficient(fish.get()))
                            : List.of();
                },
                "[the] harbor market (coefficient|rate) of [fish] %string%");
        r.number(
                Spec.single("harbor next market coefficient"),
                (e, x) -> {
                    Optional<FishId> fish = Args.string(e, x, 0).flatMap(EconomySyntax::fish);
                    Optional<PriceService> p = prices();
                    if (fish.isEmpty() || p.isEmpty()) {
                        return List.of();
                    }
                    OptionalDouble next = p.get().nextCoefficient(fish.get());
                    return next.isPresent() ? List.of(next.getAsDouble()) : List.of();
                },
                "[the] harbor next market (coefficient|rate) of [fish] %string%");
        r.number(
                Spec.of("harbor sell value"),
                (e, x) -> {
                    Optional<PriceService> p = prices();
                    if (p.isEmpty() || !(Args.single(e, x, 1) instanceof Player player)) {
                        return List.of();
                    }
                    return perFish(e, x, f -> p.get().quote(player.getUniqueId(), f));
                },
                "[the] harbor sell (value|price) of %itemstacks% for %player%");
        r.number(
                Spec.of("harbor base price"),
                (e, x) -> {
                    Optional<PriceService> p = prices();
                    return p.isEmpty() ? List.of() : perFish(e, x, f -> p.get().basePrice(f));
                },
                "[the] harbor base (value|price) of %itemstacks%");
        r.number(
                Spec.single("harbor repair cost"),
                (e, x) -> repairQuote(e, x, false),
                "[the] harbor repair cost of [price] %number% for %player%");
        r.number(
                Spec.single("harbor broken repair cost"),
                (e, x) -> repairQuote(e, x, true),
                "[the] harbor broken repair cost of [price] %number% for %player%");
    }

    /** Price per fish stack (× amount); non-fish and unsellable stacks are skipped. */
    private static List<Number> perFish(
            Event e, Expression<?>[] x, Function<HarborItem.Fish, OptionalLong> price) {
        List<Number> out = new ArrayList<>();
        if (x.length == 0 || x[0] == null) {
            return out;
        }
        for (Object o : x[0].getArray(e)) {
            if (o instanceof ItemStack stack
                    && Harbor.api().items().read(stack).orElse(null) instanceof HarborItem.Fish f) {
                OptionalLong v = price.apply(f);
                if (v.isPresent()) {
                    out.add(v.getAsLong() * stack.getAmount());
                }
            }
        }
        return out;
    }

    private static List<Number> repairQuote(Event e, Expression<?>[] x, boolean broken) {
        Optional<RepairService> repair = service(RepairService.class);
        long price = Args.number(e, x, 0).map(Number::longValue).orElse(-1L);
        if (repair.isEmpty() || price < 0 || !(Args.single(e, x, 1) instanceof Player p)) {
            return List.of();
        }
        return List.of(repair.get().quote(p.getUniqueId(), price, broken));
    }

    // ------------------------------------------------------------------ screens

    private static void screens(Registrar r) {
        r.effect(
                Spec.of("open harbor shop"),
                (e, x) -> {
                    Optional<HarborId> shop =
                            Args.string(e, x, 0).flatMap(s -> Ids.parse(s, "shop/"));
                    Optional<ShopService> s = service(ShopService.class);
                    if (shop.isEmpty() || s.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        s.get().open(p, shop.get());
                    }
                },
                "open harbor shop %string% (to|for) %players%");
        r.effect(
                Spec.of("open harbor craft station"),
                (e, x) -> {
                    Optional<HarborId> station =
                            Args.string(e, x, 0).flatMap(s -> Ids.parse(s, "station/"));
                    Optional<CraftService> c = service(CraftService.class);
                    if (station.isEmpty() || c.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        c.get().openStation(p, station.get());
                    }
                },
                "open harbor craft[ing] station %string% (to|for) %players%");
        r.effect(
                Spec.of("open harbor craft kind"),
                (e, x) -> {
                    String kind = Args.string(e, x, 0).orElse("");
                    Optional<CraftService> c = service(CraftService.class);
                    if (kind.isEmpty() || c.isEmpty()) {
                        return;
                    }
                    for (Player p : Args.players(e, x, 1)) {
                        c.get().openKind(p, kind);
                    }
                },
                "open harbor craft[ing] (kind|menu) %string% (to|for) %players%");
    }

    // ------------------------------------------------------------------ trades for a player

    /** Result targets appended to trade patterns (result key, then gold). */
    private static final String STORE =
            " [and (store|save) [the] result in %-objects%] [with [the] gold in %-objects%]";

    /** [status key] and [gold] for the two result targets. */
    private static CompletableFuture<List<?>[]> trade(CompletableFuture<TradeResult> future) {
        return future.thenApply(
                t -> new List<?>[] {List.of(t.status().key()), List.<Object>of(t.gold())});
    }

    /** Runs the trade if the player argument and the service are there (null = no result). */
    private static <S> @Nullable CompletableFuture<List<?>[]> withPlayer(
            Ctx c,
            Class<S> service,
            java.util.function.BiFunction<Player, S, @Nullable CompletableFuture<TradeResult>>
                    run) {
        Optional<Player> p = c.player(0);
        Optional<S> s = service(service);
        if (p.isEmpty() || s.isEmpty()) {
            return null;
        }
        CompletableFuture<TradeResult> future = run.apply(p.get(), s.get());
        return future == null ? null : trade(future);
    }

    /**
     * Sell, shop buy, auction listing and purchase, crafting and repair done for a player through
     * the same Economy flows as their own screens (TradeGuard, ledger, held items returned on
     * failure), without the confirmation dialogs. The trigger waits for the transaction like {@code
     * wait}; the result is a {@link TradeResult.Status} key.
     */
    private static void trades(Registrar r) {
        Ext.await(
                r,
                "make player sell held harbor fish",
                new int[] {1, 2},
                c -> withPlayer(c, SaleService.class, (p, s) -> s.sellHeld(p)),
                "make %player% sell [the] held harbor fish" + STORE);
        Ext.await(
                r,
                "make player sell all harbor fish",
                new int[] {2, 3},
                c ->
                        withPlayer(
                                c,
                                SaleService.class,
                                (p, s) -> {
                                    Optional<String> raw = c.string(1);
                                    Optional<FishId> species = raw.flatMap(EconomySyntax::fish);
                                    return raw.isPresent() && species.isEmpty()
                                            ? null
                                            : s.sellInventory(p, species.orElse(null));
                                }),
                "make %player% sell all [[of] [the]] harbor fish [of species %-string%]" + STORE);
        Ext.await(
                r,
                "make player sell harbor sell box",
                new int[] {1, 2},
                c -> withPlayer(c, SaleService.class, (p, s) -> s.sellBox(p)),
                "make %player% sell [the] [contents of [the]] harbor sell (box|chest)" + STORE);
        Ext.await(
                r,
                "make player buy harbor shop entry",
                new int[] {4, 5},
                c ->
                        withPlayer(
                                c,
                                ShopService.class,
                                (p, s) -> {
                                    int amount = c.number(1).map(Number::intValue).orElse(0);
                                    Optional<String> entry = c.string(2);
                                    Optional<HarborId> shop =
                                            c.string(3).flatMap(x -> Ids.parse(x, "shop/"));
                                    return amount <= 0 || entry.isEmpty() || shop.isEmpty()
                                            ? null
                                            : s.buy(p, shop.get(), entry.get(), amount);
                                }),
                "make %player% buy %number% [of] harbor shop entry %string% from [shop] %string%"
                        + STORE);
        Ext.await(
                r,
                "make player list held item on harbor auction",
                new int[] {2, 3},
                c -> {
                    Optional<Player> p = c.player(0);
                    Optional<AuctionService> s = service(AuctionService.class);
                    long price = c.number(1).map(Number::longValue).orElse(0L);
                    if (p.isEmpty() || s.isEmpty() || price <= 0) {
                        return null;
                    }
                    return s.get()
                            .listHeld(p.get(), price)
                            .thenApply(
                                    t ->
                                            new List<?>[] {
                                                List.of(t.status().key()),
                                                t.isOk() ? List.<Object>of(t.id()) : List.of()
                                            });
                },
                "make %player% list [the] held item on [the] harbor auction for %number% [gold]"
                        + " [and (store|save) [the] result in %-objects%] [with [the] listing id"
                        + " in %-objects%]");
        Ext.await(
                r,
                "make player buy harbor auction listing",
                new int[] {2, 3},
                c ->
                        withPlayer(
                                c,
                                AuctionService.class,
                                (p, s) -> {
                                    long id = c.number(1).map(Number::longValue).orElse(0L);
                                    return id <= 0 ? null : s.buy(p, id);
                                }),
                "make %player% buy harbor auction listing %number%" + STORE);
        Ext.await(
                r,
                "make player craft harbor recipe",
                new int[] {2, 3},
                c ->
                        withPlayer(
                                c,
                                CraftService.class,
                                (p, s) -> {
                                    Optional<HarborId> recipe =
                                            c.string(1).flatMap(x -> Ids.parse(x, "recipe/"));
                                    return recipe.isEmpty() ? null : s.craft(p, recipe.get());
                                }),
                "make %player% craft harbor recipe %string%" + STORE);
        Ext.await(
                r,
                "make player repair held harbor gear",
                new int[] {1, 2},
                c -> withPlayer(c, RepairService.class, (p, s) -> s.repairHeld(p)),
                "make %player% repair [the] held harbor (gear|item)" + STORE);
    }

    // ------------------------------------------------------------------ auction and mail reads

    private static void auction(Registrar r) {
        r.number(
                Spec.single("harbor auction listings"),
                (e, x) -> {
                    if (service(AuctionService.class).isEmpty()) {
                        return List.of();
                    }
                    List<AuctionListingInfo> list =
                            LISTINGS.get(Args.string(e, x, 0).orElse("").strip());
                    return list == null
                            ? List.of()
                            : list.stream().<Number>map(AuctionListingInfo::id).toList();
                },
                "[all] [the] harbor auction listings [in category %-string%]");
        r.text(
                Spec.single("harbor auction listing item"),
                (e, x) -> listingValue(e, x, AuctionListingInfo::itemId),
                "[the] harbor auction listing item [id] of %number%");
        r.text(
                Spec.single("harbor auction listing item name"),
                (e, x) -> listingValue(e, x, AuctionListingInfo::itemName),
                "[the] harbor auction listing item name of %number%");
        r.text(
                Spec.single("harbor auction listing category"),
                (e, x) -> listingValue(e, x, AuctionListingInfo::category),
                "[the] harbor auction listing category of %number%");
        r.text(
                Spec.single("harbor auction listing seller name"),
                (e, x) -> listingValue(e, x, AuctionListingInfo::sellerName),
                "[the] harbor auction listing seller name of %number%");
        r.number(
                Spec.single("harbor auction listing price"),
                (e, x) -> listingValue(e, x, AuctionListingInfo::price),
                "[the] harbor auction listing price of %number%");
        r.number(
                Spec.single("harbor auction listing amount"),
                (e, x) -> listingValue(e, x, AuctionListingInfo::amount),
                "[the] harbor auction listing amount of %number%");
        r.number(
                Spec.single("harbor auction listing seconds left"),
                (e, x) ->
                        listingValue(
                                e,
                                x,
                                l ->
                                        Math.max(
                                                0,
                                                l.expiresAt().getEpochSecond()
                                                        - Instant.now().getEpochSecond())),
                "[the] harbor auction listing (seconds left|remaining seconds) of %number%");
        r.player(
                Spec.single("harbor auction listing seller"),
                (e, x) -> listingValue(e, x, l -> Bukkit.getOfflinePlayer(l.seller())),
                "[the] harbor auction listing seller of %number%");
        r.number(
                Spec.single("harbor lowest auction price"),
                (e, x) -> {
                    String item = Args.string(e, x, 0).orElse("").strip();
                    if (item.isEmpty() || service(AuctionService.class).isEmpty()) {
                        return List.of();
                    }
                    String id = item.startsWith("harbor:") ? item : "harbor:" + item;
                    OptionalLong v = LOWEST.get(id);
                    return v != null && v.isPresent() ? List.of(v.getAsLong()) : List.of();
                },
                "[the] harbor lowest auction price of %string%");
        r.number(
                Spec.of("harbor auction listing count"),
                (e, x) ->
                        service(AuctionService.class).isEmpty()
                                ? List.of()
                                : perOffline(e, x, ACTIVE_COUNT::get),
                "[the] (number|amount) of harbor auction listings of %offlineplayers%");
        r.number(
                Spec.of("harbor unclaimed mail count"),
                (e, x) ->
                        Harbor.api().mail().isEmpty()
                                ? List.of()
                                : perOffline(e, x, UNCLAIMED::get),
                "[the] (number|amount) of unclaimed harbor mail[s] of %offlineplayers%");
    }

    /** Value of a listing from the cached listings (searched in every cached category). */
    private static <V> List<V> listingValue(
            Event e, Expression<?>[] x, Function<AuctionListingInfo, V> f) {
        long id = Args.number(e, x, 0).map(Number::longValue).orElse(-1L);
        if (id < 0) {
            return List.of();
        }
        List<AuctionListingInfo> all = LISTINGS.get("");
        if (all != null) {
            for (AuctionListingInfo l : all) {
                if (l.id() == id) {
                    return List.of(f.apply(l));
                }
            }
        }
        return List.of();
    }

    private static List<Number> perOffline(
            Event e, Expression<?>[] x, Function<UUID, @Nullable Integer> f) {
        List<Number> out = new ArrayList<>();
        if (x.length == 0 || x[0] == null) {
            return out;
        }
        for (Object o : x[0].getArray(e)) {
            if (o instanceof OfflinePlayer p) {
                Integer v = f.apply(p.getUniqueId());
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ api events

    private static OfflinePlayer offline(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }

    private static void events(Registrar r) {
        r.event(
                "Harbor Auction Sold",
                AuctionSoldEvent.class,
                "경매 물건이 팔린 뒤 (event-number = 낙찰가, event-string = 아이템 ID, harbor auction buyer · seller)",
                "harbor auction sold",
                "harbor auction sale");
        r.value(AuctionSoldEvent.class, Number.class, AuctionSoldEvent::price);
        r.value(AuctionSoldEvent.class, String.class, AuctionSoldEvent::itemId);
        r.player(
                Spec.in("harbor auction buyer", AuctionSoldEvent.class),
                (e, x) -> e instanceof AuctionSoldEvent s ? List.of(offline(s.buyer())) : List.of(),
                "[the] harbor auction buyer");
        r.player(
                Spec.single("harbor auction seller"),
                (e, x) -> {
                    if (e instanceof AuctionSoldEvent s) {
                        return List.of(offline(s.seller()));
                    }
                    return e instanceof AuctionListedEvent l
                            ? List.of(offline(l.seller()))
                            : List.of();
                },
                "[the] harbor auction seller");

        r.event(
                "Harbor Auction Listed",
                AuctionListedEvent.class,
                "경매에 물건을 올린 뒤 (event-offlineplayer = 판매자, event-number = 가격, event-string ="
                        + " 아이템 ID)",
                "harbor auction list[ed]");
        r.value(AuctionListedEvent.class, OfflinePlayer.class, e -> offline(e.seller()));
        r.value(AuctionListedEvent.class, Number.class, AuctionListedEvent::price);
        r.value(AuctionListedEvent.class, String.class, AuctionListedEvent::itemId);

        r.number(
                Spec.single("harbor auction listing id"),
                (e, x) -> {
                    if (e instanceof AuctionSoldEvent s) {
                        return List.of(s.listingId());
                    }
                    return e instanceof AuctionListedEvent l ? List.of(l.listingId()) : List.of();
                },
                "[the] harbor event auction listing id");
        r.number(
                Spec.single("harbor auction fee"),
                (e, x) -> {
                    if (e instanceof AuctionSoldEvent s) {
                        return List.of(s.fee());
                    }
                    return e instanceof AuctionListedEvent l ? List.of(l.fee()) : List.of();
                },
                "[the] harbor auction fee");
        r.number(
                Spec.in("harbor auction listed amount", AuctionListedEvent.class),
                (e, x) -> e instanceof AuctionListedEvent l ? List.of(l.amount()) : List.of(),
                "[the] harbor auction listed amount");
        r.condition(
                Spec.single("harbor auction proceeds held"),
                (e, x) ->
                        (e instanceof AuctionSoldEvent s && s.held())
                                || (e instanceof TradeFlaggedEvent t && t.held()),
                "[the] harbor (sale|trade) is held",
                "[the] harbor (sale|trade) (isn't|is not) held");

        r.event(
                "Harbor Fish Sold",
                FishSoldEvent.class,
                "NPC에게 물고기를 판 뒤 (event-offlineplayer, event-number = 합계 골드, harbor sold fish = 어종 ID"
                        + " 목록)",
                "harbor fish sold",
                "harbor fish sale");
        r.value(FishSoldEvent.class, OfflinePlayer.class, e -> offline(e.player()));
        r.value(FishSoldEvent.class, Number.class, FishSoldEvent::total);
        r.text(
                Spec.in("harbor sold fish", FishSoldEvent.class),
                (e, x) ->
                        e instanceof FishSoldEvent s
                                ? s.lines().stream().map(l -> l.fish().toString()).toList()
                                : List.of(),
                "[the] harbor sold fish");
        r.number(
                Spec.in("harbor sold fish count", FishSoldEvent.class),
                (e, x) -> e instanceof FishSoldEvent s ? List.of(s.lines().size()) : List.of(),
                "[the] (number|amount) of harbor sold fish");

        r.event(
                "Harbor Item Craft",
                ItemCraftedEvent.class,
                "제작이 끝나 결과물을 준 뒤 (event-offlineplayer, event-string = 결과 아이템 ID, event-number ="
                        + " 개수, harbor craft recipe · gold)",
                "harbor [item] craft[ed]");
        r.value(ItemCraftedEvent.class, OfflinePlayer.class, e -> offline(e.player()));
        r.value(ItemCraftedEvent.class, String.class, e -> e.result().toString());
        r.value(ItemCraftedEvent.class, Number.class, ItemCraftedEvent::amount);
        r.text(
                Spec.in("harbor craft recipe", ItemCraftedEvent.class),
                (e, x) ->
                        e instanceof ItemCraftedEvent c
                                ? List.of(c.recipe().toString())
                                : List.of(),
                "[the] harbor craft recipe");
        r.number(
                Spec.in("harbor craft gold", ItemCraftedEvent.class),
                (e, x) -> e instanceof ItemCraftedEvent c ? List.of(c.gold()) : List.of(),
                "[the] harbor craft (gold|cost)");

        r.event(
                "Harbor Market Update",
                MarketUpdatedEvent.class,
                "시세표가 새 값으로 바뀐 뒤 (매시)",
                "harbor market update[d]");

        r.event(
                "Harbor Shop Purchase",
                ShopPurchaseEvent.class,
                "NPC 상점 구매가 끝난 뒤 (event-offlineplayer, event-string = 상점 항목 키, event-number ="
                        + " 가격, harbor purchase shop · item · amount)",
                "harbor shop purchase");
        r.value(ShopPurchaseEvent.class, OfflinePlayer.class, e -> offline(e.player()));
        r.value(ShopPurchaseEvent.class, String.class, ShopPurchaseEvent::key);
        r.value(ShopPurchaseEvent.class, Number.class, ShopPurchaseEvent::price);
        r.text(
                Spec.in("harbor purchase shop", ShopPurchaseEvent.class),
                (e, x) ->
                        e instanceof ShopPurchaseEvent s ? List.of(s.shop().toString()) : List.of(),
                "[the] harbor purchase shop");
        r.text(
                Spec.in("harbor purchase item", ShopPurchaseEvent.class),
                (e, x) -> {
                    if (!(e instanceof ShopPurchaseEvent s)) {
                        return List.of();
                    }
                    var item = s.item();
                    return item == null ? List.of() : List.of(item.toString());
                },
                "[the] harbor purchase item");
        r.number(
                Spec.in("harbor purchase amount", ShopPurchaseEvent.class),
                (e, x) -> e instanceof ShopPurchaseEvent s ? List.of(s.amount()) : List.of(),
                "[the] harbor purchase amount");

        r.event(
                "Harbor Trade Flag",
                TradeFlaggedEvent.class,
                "부정 거래 의심 기록 뒤 (event-number = 점수, event-string = 참조 ID, harbor trade buyer ·"
                        + " seller · reasons)",
                "harbor trade flag[ged]");
        r.value(TradeFlaggedEvent.class, Number.class, TradeFlaggedEvent::score);
        r.value(TradeFlaggedEvent.class, String.class, TradeFlaggedEvent::refId);
        r.player(
                Spec.in("harbor trade buyer", TradeFlaggedEvent.class),
                (e, x) ->
                        e instanceof TradeFlaggedEvent t ? List.of(offline(t.buyer())) : List.of(),
                "[the] harbor trade buyer");
        r.player(
                Spec.in("harbor trade seller", TradeFlaggedEvent.class),
                (e, x) ->
                        e instanceof TradeFlaggedEvent t ? List.of(offline(t.seller())) : List.of(),
                "[the] harbor trade seller");
        r.text(
                Spec.in("harbor trade reasons", TradeFlaggedEvent.class),
                (e, x) -> e instanceof TradeFlaggedEvent t ? t.reasons() : List.of(),
                "[the] harbor trade reasons");
    }
}
