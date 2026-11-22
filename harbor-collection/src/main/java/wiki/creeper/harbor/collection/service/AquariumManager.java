package wiki.creeper.harbor.collection.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.AquariumService;
import wiki.creeper.harbor.collection.api.event.AquariumDonatedEvent;
import wiki.creeper.harbor.collection.api.event.AquariumStageChangedEvent;
import wiki.creeper.harbor.collection.aquarium.AquariumTanks;
import wiki.creeper.harbor.collection.content.AquariumRules;
import wiki.creeper.harbor.collection.content.StatBonus;
import wiki.creeper.harbor.collection.data.CodexData;
import wiki.creeper.harbor.collection.data.CollectionRepository;
import wiki.creeper.harbor.collection.data.CollectionRepository.AquariumState;
import wiki.creeper.harbor.collection.data.CollectionRepository.SpeciesRow;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.model.Grade;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;
import wiki.creeper.harbor.common.redis.DistributedLock;
import wiki.creeper.harbor.core.api.HandoffBlocker;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborItem;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.Modifier;
import wiki.creeper.harbor.core.api.event.ContentReloadedEvent;
import wiki.creeper.harbor.core.item.FishLedger;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 항구 수족관 (docs 20 4.4). 단계와 클라이맥스 조건은 점수가 아니라 기부율로 판정해, 한 어종을 대량으로 기부해서 밀어붙이는 것을 막는다.
 *
 * <ul>
 *   <li>기부 경로: 손에 든 한 마리(/수족관 기부), 기부함 GUI(이슬 대화 aquarium:donate), Quest 수집 의뢰({@link
 *       #donateItems}).
 *   <li>여러 마리는 한 트랜잭션: aquarium_donation 행 추가, aquarium_species INSERT IGNORE, aquarium_state 증가,
 *       고유 ID 장부 ACTIVE → DONATED. 실패하면 전부 돌려주고, 오프라인·인벤토리가 차 있으면 우편함으로 보낸다 (0.1).
 *   <li>기부 중에는 서버 이동을 최대 4초 미룬다 (Core HandoffBlocker).
 *   <li>수조 연출·구조물은 {@link AquariumTanks}.
 * </ul>
 */
public final class AquariumManager implements AquariumService, Listener {

    /** 다른 서버 동기화 (harbor:collection, 추가 제안). */
    public record AquariumUpdated(int season, int species, int stage) {}

    static final Channel<AquariumUpdated> UPDATES =
            new Channel<>("harbor:collection", AquariumUpdated.class);

    private static final String BUFF_SOURCE = "aquarium_stage";
    private static final String RETURN_SOURCE = "aquarium_return";
    private static final int BOX_ROWS = 4;
    private static final long CONFIRM_TIMEOUT_TICKS = 20L * 60;

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final ItemReturns returns;
    private final AquariumTanks tanks;
    private final Map<UUID, CompletableFuture<?>> busy = new ConcurrentHashMap<>();
    private final Map<UUID, List<ItemStack>> pendingBox = new HashMap<>();
    private volatile AquariumState state = AquariumState.EMPTY;
    private volatile Set<FishId> donatedSpecies = Set.of();

    /** 기부함 GUI (아이템을 넣고 닫으면 확인 화면). Core Menu와 달리 아이템 이동을 막지 않는다. */
    static final class DonationBox implements InventoryHolder {
        private final Inventory inventory;

        DonationBox(Component title) {
            this.inventory = Bukkit.createInventory(this, BOX_ROWS * 9, title);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** 기부할 한 마리. */
    private record Candidate(
            ItemStack stack, HarborItem.Fish item, FishDef fish, boolean counted) {}

    public AquariumManager(HarborApi api, Tasks tasks, Logger log, ItemReturns returns) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.returns = returns;
        this.tanks = new AquariumTanks(api, tasks, log);
    }

    public void start() {
        refresh();
        tanks.start();
        api.bus()
                .subscribe(
                        UPDATES,
                        envelope -> {
                            if (!envelope.server().equals(api.infra().serverId())) {
                                tasks.main().execute(this::refresh);
                            }
                        });
    }

    /** 종료: 열린 기부함·확인 대기 아이템을 돌려주고 수조 리그를 지운다. */
    public void stop() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder(false)
                    instanceof DonationBox box) {
                List<ItemStack> items = takeAll(box.getInventory());
                p.closeInventory();
                p.getInventory().addItem(items.toArray(ItemStack[]::new));
            }
        }
        pendingBox.forEach(
                (uuid, items) -> {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        p.getInventory().addItem(items.toArray(ItemStack[]::new));
                    }
                });
        pendingBox.clear();
        tanks.stop();
    }

    /** DB에서 다시 읽고 버프·수조를 교체한다 (메인 스레드에서 부른다). */
    public void refresh() {
        SeasonId season = api.infra().season();
        record Loaded(AquariumState state, List<SpeciesRow> species) {}
        var _ =
                api.database()
                        .async(
                                c ->
                                        new Loaded(
                                                CollectionRepository.aquarium(c, season),
                                                CollectionRepository.species(c, season)))
                        .thenAcceptAsync(
                                loaded -> {
                                    AquariumState s = loaded.state();
                                    List<SpeciesRow> rows = loaded.species();
                                    state = s;
                                    Set<FishId> ids = new HashSet<>();
                                    rows.forEach(r -> ids.add(r.fish()));
                                    donatedSpecies = Set.copyOf(ids);
                                    applyBuffs(s.stage());
                                    tanks.update(rows, s.stage());
                                },
                                tasks.main())
                        .exceptionally(
                                e -> {
                                    log.warn(
                                            "수족관 상태를 읽지 못했습니다: {}", String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    AquariumRules rules() {
        return api.content().snapshot().single(AquariumRules.TYPE);
    }

    /** 기부 가능 어종 수: 시즌 어종 중 신화·release_only 제외. */
    int donatable() {
        int season = api.infra().season().value();
        return (int)
                api.content().snapshot().all(FishDef.TYPE).values().stream()
                        .filter(f -> donatableFish(f, season))
                        .count();
    }

    static boolean donatableFish(FishDef f, int season) {
        return f.season() <= season && f.grade() != Grade.MYTHIC && !f.releaseOnly();
    }

    @Override
    public Status status() {
        AquariumState s = state;
        int donatable = donatable();
        return new Status(
                s.points(),
                s.speciesCount(),
                donatable,
                donatable == 0 ? 0 : (double) s.speciesCount() / donatable,
                s.stage());
    }

    /** 다음 단계 (/수족관 표시). */
    public Optional<AquariumRules.Stage> nextStage(Status status) {
        return rules().next(status.stage());
    }

    /** 이번 시즌 처음 기부되는 어종인지 (메모리 캐시 기준, 확인 화면 표시용. 점수는 트랜잭션 안에서 다시 판정). */
    boolean firstSpecies(FishId fish) {
        return !donatedSpecies.contains(fish);
    }

    /** 서버 이동 지연 (Core HandoffBlocker): 기부 트랜잭션이 끝날 때까지. */
    public HandoffBlocker handoffBlocker() {
        return (player, destination) -> {
            // 기부함이 열려 있거나 확인을 기다리는 동안은 아이템이 인벤토리 밖(기부함·대기 목록)에 있다. 저장본을 뜨면 빠지고, 뜬 뒤 돌려주는
            // 아이템은 저장되지 않으므로 이동을 막는다
            if (pendingBox.containsKey(player.getUniqueId())
                    || player.getOpenInventory().getTopInventory().getHolder(false)
                            instanceof DonationBox) {
                return new HandoffBlocker.Deny("busy");
            }
            CompletableFuture<?> f = busy.get(player.getUniqueId());
            return f == null || f.isDone() ? HandoffBlocker.ALLOW : new HandoffBlocker.Delay(f);
        };
    }

    // ------------------------------------------------------------------ 손에 든 한 마리

    @Override
    public CompletableFuture<Boolean> donateHeld(Player player) {
        return donateSlot(
                player,
                player.getInventory().getHeldItemSlot(),
                "hand",
                "collection.aquarium.changed");
    }

    /**
     * 인벤토리 한 칸의 어종 한 마리를 기부한다 (확인 화면 후, 손에 든 것·수족관 진행 화면 공용). 확인하는 사이 그 칸의 아이템이 바뀌었으면 취소하고, 기부는
     * {@link #donateItems}의 같은 트랜잭션으로 한다.
     *
     * @param changedKey 칸이 바뀌었을 때 문구 키
     */
    CompletableFuture<Boolean> donateSlot(
            Player player, int slot, String source, String changedKey) {
        ItemStack held = player.getInventory().getItem(slot);
        Optional<HarborItem.Fish> item = fishOf(held);
        Optional<FishDef> fish =
                item.flatMap(i -> api.content().snapshot().get(FishDef.TYPE, i.species().id()));
        if (item.isEmpty() || fish.isEmpty() || fish.get().releaseOnly()) {
            api.messages().send(player, "collection.aquarium.not_fish");
            return CompletableFuture.completedFuture(false);
        }
        HarborPlayer hp = api.players().get(player);
        CodexData data = hp.get(CollectionManager.COMPONENT);
        AquariumRules rules = rules();
        boolean counted =
                data.donations().getOrDefault(fish.get().id(), 0) < rules.perPlayerSpeciesCap();
        boolean first = firstSpecies(fish.get().id());
        int estimate =
                counted
                        ? rules.pointsFor(fish.get().grade(), item.get().quality().stars(), first)
                        : 0;
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        List<Component> body = new ArrayList<>();
        body.add(
                api.messages()
                        .get(
                                "collection.aquarium.confirm_body",
                                Placeholder.unparsed("name", fish.get().name()),
                                Placeholder.unparsed("points", String.valueOf(estimate))));
        if (first) {
            body.add(api.messages().get("collection.aquarium.first_species"));
        }
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("collection.aquarium.confirm_title"),
                        body,
                        api.messages().get("collection.aquarium.yes"),
                        api.messages().get("collection.aquarium.no"),
                        ok -> {
                            if (!ok) {
                                result.complete(false);
                                return;
                            }
                            // 그 칸의 아이템이 확인 화면 사이에 바뀌었으면 취소
                            ItemStack now = player.getInventory().getItem(slot);
                            if (now == null || !fishOf(now).map(item.get()::equals).orElse(false)) {
                                api.messages().send(player, changedKey);
                                result.complete(false);
                                return;
                            }
                            ItemStack taken = now.asOne();
                            now.setAmount(now.getAmount() - 1);
                            var _ =
                                    donateItems(player, List.of(taken), source)
                                            .thenAccept(r -> result.complete(r.ok()));
                        });
        return result;
    }

    /** 이번 시즌 기부된 어종 (메모리 캐시, 진행 화면). */
    Set<FishId> donatedSpecies() {
        return donatedSpecies;
    }

    // ------------------------------------------------------------------ 기부함

    /** 자동 저장용 (Core ItemCustody): 열린 기부함에 넣은 아이템과 기부 확인을 기다리는 아이템. */
    public List<ItemStack> held(Player player) {
        List<ItemStack> out = new ArrayList<>();
        if (player.getOpenInventory().getTopInventory().getHolder(false)
                instanceof DonationBox box) {
            for (ItemStack stack : box.getInventory().getContents()) {
                if (stack != null && !stack.isEmpty()) {
                    out.add(stack.clone());
                }
            }
        }
        List<ItemStack> pending = pendingBox.get(player.getUniqueId());
        if (pending != null) {
            pending.forEach(stack -> out.add(stack.clone()));
        }
        return out;
    }

    @Override
    public void openDonationBox(Player player) {
        if (pendingBox.containsKey(player.getUniqueId())) {
            api.messages().send(player, "collection.aquarium.box_pending");
            return;
        }
        player.openInventory(
                new DonationBox(api.messages().get("collection.aquarium.box_title"))
                        .getInventory());
    }

    @EventHandler
    public void onBoxClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof DonationBox box)
                || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        List<ItemStack> items = takeAll(box.getInventory());
        if (items.isEmpty()) {
            return;
        }
        // 다음 틱: 닫는 이벤트 안에서 다른 화면을 열지 않는다
        var _ = tasks.later(1, () -> confirmBox(player, items));
    }

    private void confirmBox(Player player, List<ItemStack> items) {
        if (!player.isOnline()) {
            returns.giveBack(player.getUniqueId(), items, RETURN_SOURCE);
            return;
        }
        List<ItemStack> fish = new ArrayList<>();
        List<ItemStack> other = new ArrayList<>();
        for (ItemStack stack : items) {
            Optional<FishDef> def =
                    fishOf(stack)
                            .flatMap(
                                    i ->
                                            api.content()
                                                    .snapshot()
                                                    .get(FishDef.TYPE, i.species().id()));
            if (def.isPresent() && !def.get().releaseOnly()) {
                fish.add(stack);
            } else {
                other.add(stack);
            }
        }
        if (!other.isEmpty()) {
            returns.giveBack(player.getUniqueId(), other, RETURN_SOURCE);
            api.messages().send(player, "collection.aquarium.box_returned");
        }
        if (fish.isEmpty()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        pendingBox.put(uuid, fish);
        List<Candidate> preview = candidates(player, fish, new ArrayList<>());
        AquariumRules rules = rules();
        List<Component> body = new ArrayList<>();
        int total = 0;
        Set<FishId> firstShown = new HashSet<>();
        Map<String, int[]> lines = new LinkedHashMap<>();
        for (Candidate c : preview) {
            boolean first = firstSpecies(c.fish().id()) && firstShown.add(c.fish().id());
            int points =
                    c.counted()
                            ? rules.pointsFor(c.fish().grade(), c.item().quality().stars(), first)
                            : 0;
            total += points;
            String key = c.fish().name() + "|" + c.item().quality().display() + "|" + first;
            int[] acc = lines.computeIfAbsent(key, k -> new int[2]);
            acc[0]++;
            acc[1] += points;
        }
        lines.forEach(
                (key, acc) -> {
                    String[] parts = key.split("\\|", 3);
                    body.add(
                            api.messages()
                                    .get(
                                            parts[2].equals("true")
                                                    ? "collection.aquarium.box_line_first"
                                                    : "collection.aquarium.box_line",
                                            Placeholder.unparsed("name", parts[0]),
                                            Placeholder.unparsed("quality", parts[1]),
                                            Placeholder.unparsed("count", String.valueOf(acc[0])),
                                            Placeholder.unparsed(
                                                    "points", String.valueOf(acc[1]))));
                });
        body.add(
                api.messages()
                        .get(
                                "collection.aquarium.box_total",
                                Placeholder.unparsed("count", String.valueOf(preview.size())),
                                Placeholder.unparsed("points", String.valueOf(total))));
        api.dialogs()
                .confirm(
                        player,
                        api.messages().get("collection.aquarium.confirm_title"),
                        body,
                        api.messages().get("collection.aquarium.yes"),
                        api.messages().get("collection.aquarium.no"),
                        ok -> {
                            List<ItemStack> held = pendingBox.remove(uuid);
                            if (held == null) {
                                return;
                            }
                            if (ok) {
                                var _ = donateItems(player, held, "box");
                            } else {
                                returns.giveBack(uuid, held, RETURN_SOURCE);
                            }
                        });
        // ESC로 닫으면 결과가 오지 않으므로 시간이 지나면 돌려준다
        var _ =
                tasks.later(
                        CONFIRM_TIMEOUT_TICKS,
                        () -> {
                            List<ItemStack> held = pendingBox.remove(uuid);
                            if (held != null) {
                                returns.giveBack(uuid, held, RETURN_SOURCE);
                            }
                        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        List<ItemStack> held = pendingBox.remove(uuid);
        if (held != null) {
            // 퇴장 이벤트 안이라 아직 인벤토리가 저장되기 전이다
            event.getPlayer().getInventory().addItem(held.toArray(ItemStack[]::new));
        }
    }

    private static List<ItemStack> takeAll(Inventory inventory) {
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.isEmpty()) {
                items.add(stack.clone());
            }
        }
        inventory.clear();
        return items;
    }

    // ------------------------------------------------------------------ 여러 마리 기부

    @Override
    public CompletableFuture<DonationResult> donateItems(
            Player player, List<ItemStack> items, String source) {
        UUID uuid = player.getUniqueId();
        List<ItemStack> giveBack = new ArrayList<>();
        List<Candidate> list = candidates(player, items, giveBack);
        if (list.isEmpty()) {
            returns.giveBack(uuid, giveBack, RETURN_SOURCE);
            return CompletableFuture.completedFuture(
                    new DonationResult(0, 0, countOf(giveBack), false));
        }
        SeasonId season = api.players().get(player).season();
        AquariumRules rules = rules();
        boolean questOwned = source.startsWith("quest:");
        CompletableFuture<int[]> work =
                api.database()
                        .async(
                                c -> {
                                    int[] points = new int[list.size()];
                                    for (int i = 0; i < list.size(); i++) {
                                        Candidate cand = list.get(i);
                                        ledger(c, cand.item().uid(), questOwned);
                                        boolean first =
                                                cand.counted()
                                                        && !speciesDonated(
                                                                c, season, cand.fish().id());
                                        int p =
                                                cand.counted()
                                                        ? rules.pointsFor(
                                                                cand.fish().grade(),
                                                                cand.item().quality().stars(),
                                                                first)
                                                        : 0;
                                        CollectionRepository.donate(
                                                c,
                                                season,
                                                uuid,
                                                cand.fish().id(),
                                                cand.item().sizeCm(),
                                                cand.item().quality().stars(),
                                                cand.item().uid(),
                                                p);
                                        points[i] = p;
                                    }
                                    return points;
                                });
        busy.put(uuid, work);
        return work.handleAsync(
                (points, error) -> {
                    busy.remove(uuid, work);
                    // 기부 기록은 즉시 커밋, 인벤토리는 지연 쓰기다: 곧 저장해서 서버가 죽어도 기부한 물고기가 돌아오지 않게 한다
                    // (실패해서 돌려준 경우도 인벤토리가 바뀌었다)
                    api.players().saveSoon(uuid);
                    if (error != null || points == null) {
                        log.warn(
                                "수족관 기부 실패, 아이템을 돌려줍니다: {} {} {}",
                                player.getName(),
                                source,
                                String.valueOf(error == null ? null : error.getMessage()));
                        List<ItemStack> all = new ArrayList<>(giveBack);
                        list.forEach(c -> all.add(c.stack()));
                        returns.giveBack(uuid, all, RETURN_SOURCE);
                        if (player.isOnline()) {
                            api.messages().send(player, "collection.aquarium.failed");
                        }
                        return new DonationResult(0, 0, countOf(all), true);
                    }
                    returns.giveBack(uuid, giveBack, RETURN_SOURCE);
                    int total = 0;
                    Optional<HarborPlayer> hp = api.players().get(uuid);
                    Set<FishId> newSpecies = new HashSet<>(donatedSpecies);
                    for (int i = 0; i < list.size(); i++) {
                        Candidate cand = list.get(i);
                        total += points[i];
                        newSpecies.add(cand.fish().id());
                        hp.ifPresent(
                                h ->
                                        h.set(
                                                CollectionManager.COMPONENT,
                                                h.get(CollectionManager.COMPONENT)
                                                        .withDonation(cand.fish().id())));
                        Bukkit.getPluginManager()
                                .callEvent(
                                        new AquariumDonatedEvent(
                                                uuid,
                                                cand.fish().id(),
                                                cand.fish().grade(),
                                                cand.item().quality(),
                                                points[i]));
                    }
                    donatedSpecies = Set.copyOf(newSpecies);
                    if (player.isOnline()) {
                        if (list.size() == 1) {
                            api.messages()
                                    .send(
                                            player,
                                            "collection.aquarium.donated",
                                            Placeholder.unparsed(
                                                    "name", list.getFirst().fish().name()),
                                            Placeholder.unparsed("points", String.valueOf(total)));
                        } else {
                            api.messages()
                                    .send(
                                            player,
                                            "collection.aquarium.donated_many",
                                            Placeholder.unparsed(
                                                    "count", String.valueOf(list.size())),
                                            Placeholder.unparsed("points", String.valueOf(total)));
                        }
                    }
                    var _ = tasks.async(this::checkStage);
                    return new DonationResult(list.size(), total, countOf(giveBack), false);
                },
                tasks.main());
    }

    /** 아이템을 한 마리씩 나눈다. 어종이 아니거나 기부할 수 없는 아이템은 giveBack에 넣는다. 개인 상한(같은 어종 시즌 N마리)은 묶음 안의 순서까지 센다. */
    private List<Candidate> candidates(
            Player player, List<ItemStack> items, List<ItemStack> giveBack) {
        HarborPlayer hp = api.players().get(player);
        Map<FishId, Integer> counts =
                new HashMap<>(hp.get(CollectionManager.COMPONENT).donations());
        int cap = rules().perPlayerSpeciesCap();
        List<Candidate> result = new ArrayList<>();
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            Optional<HarborItem.Fish> item = fishOf(stack);
            Optional<FishDef> fish =
                    item.flatMap(i -> api.content().snapshot().get(FishDef.TYPE, i.species().id()));
            if (item.isEmpty() || fish.isEmpty() || fish.get().releaseOnly()) {
                giveBack.add(stack);
                continue;
            }
            for (int n = 0; n < stack.getAmount(); n++) {
                int already = counts.merge(fish.get().id(), 1, Integer::sum) - 1;
                result.add(new Candidate(stack.asOne(), item.get(), fish.get(), already < cap));
            }
        }
        return result;
    }

    /** 고유 ID 장부: ACTIVE → DONATED. Quest가 같은 납품에서 이미 DONATED로 바꿨으면 그대로 받는다. */
    private static void ledger(Connection c, @Nullable UUID uid, boolean questOwned)
            throws SQLException {
        if (uid == null
                || FishLedger.transition(
                        c, uid, FishLedger.State.ACTIVE, FishLedger.State.DONATED)) {
            return;
        }
        if (questOwned
                && FishLedger.state(c, uid)
                        .filter(s -> s == FishLedger.State.DONATED)
                        .isPresent()) {
            return;
        }
        throw new SQLException("고유 ID 장부 상태가 ACTIVE가 아닙니다: " + uid);
    }

    private static boolean speciesDonated(Connection c, SeasonId season, FishId fish)
            throws SQLException {
        try (var ps =
                c.prepareStatement(
                        "SELECT 1 FROM aquarium_species WHERE season = ? AND fish_id = ?")) {
            ps.setInt(1, season.value());
            ps.setString(2, fish.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    Optional<HarborItem.Fish> fishOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return api.items()
                .read(stack)
                .filter(i -> i instanceof HarborItem.Fish)
                .map(i -> (HarborItem.Fish) i);
    }

    private static int countOf(List<ItemStack> items) {
        return items.stream().mapToInt(ItemStack::getAmount).sum();
    }

    // ------------------------------------------------------------------ 단계

    /** 가상 스레드: 기부율을 다시 보고, 단계를 올릴 수 있으면 분산 잠금 + 조건부 UPDATE로 한 번만 올린다. */
    void checkStage() {
        SeasonId season = api.infra().season();
        Optional<DistributedLock.Handle> lock;
        try {
            lock = api.locks().tryAcquire("harbor:lock:aquarium", Duration.ofSeconds(30));
        } catch (RuntimeException e) {
            log.warn("수족관 잠금 실패: {}", String.valueOf(e.getMessage()));
            return;
        }
        if (lock.isEmpty()) {
            return;
        }
        try {
            AquariumState current =
                    api.database().tx(c -> CollectionRepository.aquarium(c, season));
            int donatable = donatable();
            double rate = donatable == 0 ? 0 : (double) current.speciesCount() / donatable;
            int target = current.stage();
            for (AquariumRules.Stage stage : rules().stages()) {
                if (rate >= stage.rate()) {
                    target = Math.max(target, stage.stage());
                }
            }
            int finalTarget = target;
            boolean raised =
                    target > current.stage()
                            && api.database()
                                    .tx(
                                            c ->
                                                    CollectionRepository.raiseStage(
                                                            c, season, finalTarget));
            AquariumState next =
                    new AquariumState(
                            current.points(),
                            current.speciesCount(),
                            raised ? target : current.stage());
            tasks.main()
                    .execute(
                            () -> {
                                state = next;
                                if (raised) {
                                    onStageRaised(season, finalTarget);
                                }
                                api.bus()
                                        .publish(
                                                UPDATES,
                                                new AquariumUpdated(
                                                        season.value(),
                                                        next.speciesCount(),
                                                        next.stage()));
                                refresh();
                            });
        } catch (SQLException e) {
            log.error("수족관 단계 확인 실패", e);
        } finally {
            lock.get().release();
        }
    }

    /** 단계를 올린 서버만: 서버 플래그, 이벤트, 공지. */
    private void onStageRaised(SeasonId season, int stage) {
        applyBuffs(stage);
        for (AquariumRules.Stage s : rules().stages()) {
            if (s.stage() <= stage && !s.flag().isEmpty()) {
                var _ = api.flags().setServer(s.flag(), "1", true);
            }
        }
        Bukkit.getPluginManager().callEvent(new AquariumStageChangedEvent(season.value(), stage));
        Component msg =
                api.messages()
                        .get(
                                "collection.announce.aquarium_stage",
                                Placeholder.unparsed("stage", String.valueOf(stage)));
        api.bus()
                .publish(
                        Channels.ANNOUNCEMENTS,
                        new Channels.Announcement(MiniMessage.miniMessage().serialize(msg)));
    }

    /** 단계 버프 (출처 aquarium_stage, 서버 전체 보정자): 교체 등록. */
    private void applyBuffs(int stage) {
        api.modifiers().removeGlobalSource(BUFF_SOURCE);
        for (AquariumRules.Stage s : rules().stages()) {
            if (s.stage() <= stage) {
                for (StatBonus bonus : s.modifiers()) {
                    api.modifiers()
                            .addGlobal(Modifier.add(bonus.stat(), bonus.value(), BUFF_SOURCE));
                }
            }
        }
    }

    @EventHandler
    public void onReload(ContentReloadedEvent event) {
        applyBuffs(state.stage());
        tanks.reload();
    }

    /** 관리자: 기부 기록에서 다시 집계. */
    public CompletableFuture<Void> recount() {
        SeasonId season = api.infra().season();
        return api.database()
                .asyncVoid(c -> CollectionRepository.recount(c, season))
                .thenRunAsync(this::refresh, tasks.main());
    }

    /** 관리자: 단계 강제 (올리기만). */
    public CompletableFuture<Boolean> forceStage(int stage) {
        SeasonId season = api.infra().season();
        return api.database()
                .async(c -> CollectionRepository.raiseStage(c, season, stage))
                .thenApplyAsync(
                        raised -> {
                            if (raised) {
                                state =
                                        new AquariumState(
                                                state.points(), state.speciesCount(), stage);
                                onStageRaised(season, stage);
                                refresh();
                            }
                            return raised;
                        },
                        tasks.main());
    }
}
