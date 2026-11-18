package wiki.creeper.harbor.collection.display;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.CodexEntry;
import wiki.creeper.harbor.collection.api.DisplayCaseService;
import wiki.creeper.harbor.collection.api.DisplaySlot;
import wiki.creeper.harbor.collection.content.DisplayCaseRules;
import wiki.creeper.harbor.collection.data.CollectionRepository;
import wiki.creeper.harbor.collection.data.DisplayRepository;
import wiki.creeper.harbor.collection.hook.NpcBridge;
import wiki.creeper.harbor.collection.service.CollectionManager;
import wiki.creeper.harbor.collection.service.Locs;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.donor.DonorTier;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.model.Quality;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.MessageService;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.core.gui.Menu;
import wiki.creeper.harbor.core.gui.PagedMenu;
import wiki.creeper.harbor.core.task.Tasks;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.PlotPermission;
import wiki.creeper.harbor.world.api.event.DisplayAnchorChangedEvent;
import wiki.creeper.harbor.world.api.event.PlotLoadedEvent;
import wiki.creeper.harbor.world.api.event.PlotUnloadedEvent;

/**
 * 개인 어항 전시 (docs 20 4.4 '개인 어항 전시', 0.1·0.6 D11·D14). Harbor-World가 켜져 있을 때만 이 클래스를 불러온다.
 *
 * <ul>
 *   <li>수조 자리(DisplayAnchor)·편집 권한(EDIT_DISPLAY)은 World. 전시 내용(housing_display)·칸 수·헤엄 연출·방문객 정보는
 *       여기.
 *   <li>전시 칸 수 = 기본 + 시즌 간 최고 평판 등급 보너스 (D11). 칸 밖 수조는 비워 둔다 (전시 행은 지우지 않아 칸이 늘면 다시 보인다).
 *   <li>테마 칸 수 = 기본 + 후원 등급 추가 칸 (D14, Common DonorTier). 후원이 끝나 칸이 줄면 넘는 칸의 테마만 기본으로 되돌린다.
 *   <li>PlotLoadedEvent 때 칸마다 Core 리그를 띄워 플롯 안·근처 view_radius 블록 플레이어에게만 보여주고, PlotUnloadedEvent 때
 *       지운다. 플롯당 부품 part_budget개를 넘으면 간이 리그로 바꾼다.
 *   <li>칸마다 Interaction 1개: 편집 권한이 있으면 편집 화면(웅크리면 정보), 방문객은 정보 Text Display를 클릭한 사람에게만 몇 초 보여준다.
 * </ul>
 */
public final class DisplayCaseManager implements DisplayCaseService, Listener {

    private static final int VIEW_TICKS = 20;
    private static final int SWIM_TICKS = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final Plugin plugin;
    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final HousingService housing;
    private final NpcBridge npc;
    private final ShopThemes themes;
    private final Map<Long, PlotView> plots = new HashMap<>();
    private final Map<UUID, SlotRef> boxes = new HashMap<>();
    private final Set<Long> loading = new HashSet<>();
    private @Nullable BukkitTask task;
    private long ticks;

    private record SlotRef(long plotId, int slot) {}

    /** 적재된 플롯 하나. */
    private static final class PlotView {
        PlotInfo plot;
        final Map<Integer, DisplaySlot> rows = new TreeMap<>();
        final Map<Integer, SlotView> views = new HashMap<>();
        int slotCount;
        int themeSlots;
        Set<UUID> viewers = Set.of();

        PlotView(PlotInfo plot) {
            this.plot = plot;
        }
    }

    /** 그려진 칸 하나. */
    private static final class SlotView {
        final Location center;
        final Interaction box;
        @Nullable RigInstance rig;
        @Nullable BlockDisplay floor;
        @Nullable Particle particle;

        SlotView(Location center, Interaction box) {
            this.center = center;
            this.box = box;
        }

        void remove() {
            box.remove();
            RigInstance r = rig;
            if (r != null) {
                r.remove();
            }
            BlockDisplay f = floor;
            if (f != null) {
                f.remove();
            }
        }
    }

    public DisplayCaseManager(
            Plugin plugin,
            HarborApi api,
            Tasks tasks,
            Logger log,
            HousingService housing,
            NpcBridge npc,
            ShopThemes themes) {
        this.plugin = plugin;
        this.api = api;
        this.tasks = tasks;
        this.log = log;
        this.housing = housing;
        this.npc = npc;
        this.themes = themes;
    }

    DisplayCaseRules rules() {
        return api.content().snapshot().single(DisplayCaseRules.TYPE);
    }

    public void start() {
        task = tasks.timer(20, 1, this::tick);
        npc.onTierChanged(this::refreshOwner);
    }

    public void stop() {
        BukkitTask t = task;
        if (t != null) {
            t.cancel();
        }
        plots.values().forEach(this::despawn);
        plots.clear();
        boxes.clear();
    }

    // ------------------------------------------------------------------ 칸 수

    @Override
    public CompletableFuture<Integer> slotCount(UUID owner) {
        return npc.loadBestTier(owner).thenApply(tier -> rules().slotCount(tier));
    }

    @Override
    public CompletableFuture<Integer> themeSlotCount(UUID owner) {
        return CompletableFuture.supplyAsync(
                        () -> DonorTier.lookup(api.redis(), owner).extraThemeSlots(),
                        tasks.virtual())
                .thenApplyAsync(extra -> rules().themeSlotCount(extra), tasks.main());
    }

    @Override
    public CompletableFuture<List<DisplaySlot>> slots(long plotId) {
        return api.database()
                .async(c -> DisplayRepository.load(c, plotId))
                .thenApplyAsync(List::copyOf, tasks.main());
    }

    // ------------------------------------------------------------------ 적재·그리기

    @EventHandler
    public void onPlotLoaded(PlotLoadedEvent event) {
        load(event.plot());
    }

    @EventHandler
    public void onPlotUnloaded(PlotUnloadedEvent event) {
        long id = event.plot().plotId();
        loading.remove(id);
        PlotView view = plots.remove(id);
        if (view != null) {
            despawn(view);
        }
    }

    @EventHandler
    public void onAnchor(DisplayAnchorChangedEvent event) {
        PlotView view = plots.get(event.plot().plotId());
        if (view != null) {
            view.plot = event.plot();
            render(view);
        }
    }

    /** 평판 등급이 바뀌면 그 플레이어 플롯의 칸 수를 다시 계산한다 (NPC ReputationTierChangedEvent). */
    public void refreshOwner(UUID owner) {
        for (PlotView view : plots.values()) {
            if (view.plot.owner().equals(owner)) {
                view.slotCount = rules().slotCount(npc.bestTier(owner));
                render(view);
            }
        }
    }

    private void load(PlotInfo plot) {
        long id = plot.plotId();
        UUID owner = plot.owner();
        loading.add(id);
        CompletableFuture<List<DisplaySlot>> rows =
                api.database().async(c -> DisplayRepository.load(c, id));
        CompletableFuture<Integer> donor =
                CompletableFuture.supplyAsync(
                        () -> DonorTier.lookup(api.redis(), owner).extraThemeSlots(),
                        tasks.virtual());
        CompletableFuture<Integer> best = npc.loadBestTier(owner);
        var _ =
                rows.thenCombine(donor, RowsDonor::new)
                        .thenCombine(best, (rd, b) -> new Loaded(rd.rows(), rd.donorExtra(), b))
                        .thenAcceptAsync(loaded -> onLoaded(plot, loaded), tasks.main())
                        .exceptionally(
                                e -> {
                                    log.warn(
                                            "개인 어항을 읽지 못했습니다: 플롯 {} {}",
                                            id,
                                            String.valueOf(e.getMessage()));
                                    return null;
                                });
    }

    /** 비동기 결과 묶음. */
    private record RowsDonor(List<DisplaySlot> rows, int donorExtra) {}

    private record Loaded(List<DisplaySlot> rows, int donorExtra, int bestTier) {}

    private void onLoaded(PlotInfo plot, Loaded loaded) {
        long id = plot.plotId();
        if (!loading.remove(id)) {
            return; // 그 사이에 내려감
        }
        DisplayCaseRules rules = rules();
        PlotView view = new PlotView(plot);
        loaded.rows().forEach(r -> view.rows.put(r.slot(), r));
        view.slotCount = rules.slotCount(loaded.bestTier());
        view.themeSlots = rules.themeSlotCount(loaded.donorExtra());
        // D14: 후원이 끝나 테마 칸이 줄었으면 넘는 칸의 테마만 기본으로
        List<Integer> themed = new ArrayList<>();
        view.rows
                .values()
                .forEach(
                        r -> {
                            if (r.theme() != null) {
                                themed.add(r.slot());
                            }
                        });
        List<Integer> revert = DisplayCaseRules.themesToRevert(themed, view.themeSlots);
        if (!revert.isEmpty()) {
            for (int slot : revert) {
                DisplaySlot r = view.rows.get(slot);
                if (r != null) {
                    view.rows.put(slot, withTheme(r, null));
                }
            }
            var _ = api.database().asyncVoid(c -> DisplayRepository.revertThemes(c, id, revert));
            Player owner = Bukkit.getPlayer(plot.owner());
            if (owner != null) {
                api.messages().send(owner, "collection.display.theme_reverted");
            }
        }
        PlotView old = plots.put(id, view);
        if (old != null) {
            despawn(old);
        }
        render(view);
    }

    private void despawn(PlotView view) {
        view.views
                .values()
                .forEach(
                        v -> {
                            boxes.remove(v.box.getUniqueId());
                            v.remove();
                        });
        view.views.clear();
    }

    /** 플롯의 칸을 모두 다시 그린다. */
    private void render(PlotView view) {
        despawn(view);
        World world = Bukkit.getWorld(view.plot.world());
        if (world == null) {
            return;
        }
        DisplayCaseRules rules = rules();
        List<DisplayAnchor> anchors = new ArrayList<>(housing.displayAnchors(view.plot.plotId()));
        anchors.sort(Comparator.comparingInt(DisplayAnchor::slot));
        // 부품 상한: 칸 순서대로 원래 리그 → 넘으면 간이 리그 → 그래도 넘으면 표시 안 함
        List<DisplayAnchor> shown = new ArrayList<>();
        List<Integer> parts = new ArrayList<>();
        for (DisplayAnchor a : anchors) {
            DisplaySlot row = view.rows.get(a.slot());
            if (row != null && DisplayCaseRules.slotOpen(a.slot(), view.slotCount)) {
                shown.add(a);
                parts.add(
                        rigFor(row.fish())
                                .flatMap(r -> api.rigs().definition(r))
                                .map(RigDef::partCount)
                                .orElse(1));
            }
        }
        RigId simple = rules.simpleRig();
        boolean hasSimple = simple != null && api.rigs().ids().contains(simple);
        int[] budget = DisplayCaseRules.budget(parts, rules.partBudget(), hasSimple);
        Map<Integer, Integer> plan = new HashMap<>();
        for (int i = 0; i < shown.size(); i++) {
            plan.put(shown.get(i).slot(), budget[i]);
        }
        List<Player> viewers = viewers(view, world);
        view.viewers = ids(viewers);
        for (DisplayAnchor a : anchors) {
            if (!a.world().equals(world.getName())
                    || !world.isChunkLoaded(a.x() >> 4, a.z() >> 4)) {
                continue;
            }
            Location center = new Location(world, a.x() + 0.5, a.y() + 0.5, a.z() + 0.5);
            Interaction box =
                    world.spawn(
                            new Location(world, a.x() + 0.5, a.y(), a.z() + 0.5),
                            Interaction.class,
                            e -> {
                                e.setPersistent(false);
                                e.setInteractionWidth(1.0f);
                                e.setInteractionHeight(1.0f);
                                e.setResponsive(true);
                            });
            SlotView sv = new SlotView(center, box);
            boxes.put(box.getUniqueId(), new SlotRef(view.plot.plotId(), a.slot()));
            view.views.put(a.slot(), sv);
            DisplaySlot row = view.rows.get(a.slot());
            if (row == null || !DisplayCaseRules.slotOpen(a.slot(), view.slotCount)) {
                continue;
            }
            DisplayCaseRules.Theme theme = rules.theme(row.theme()).orElse(rules.defaultTheme());
            sv.floor = floor(world, a, theme);
            sv.particle = theme.particle() == null ? null : particle(theme.particle());
            int mode = plan.getOrDefault(a.slot(), 2);
            Optional<RigId> rig =
                    mode == 0
                            ? rigFor(row.fish())
                            : mode == 1 ? Optional.ofNullable(simple) : Optional.empty();
            if (rig.isEmpty() && mode == 0 && hasSimple) {
                rig = Optional.ofNullable(simple);
            }
            if (rig.isEmpty()) {
                continue;
            }
            double length = api.rigs().definition(rig.get()).map(RigDef::length).orElse(0.5);
            double scale = Math.clamp(0.7 / Math.max(0.1, length), 0.2, 1.0);
            try {
                RigInstance instance =
                        api.rigs()
                                .spawn(
                                        rig.get(),
                                        center.clone(),
                                        RigOptions.privateTo(viewers)
                                                .withAnimation("swim")
                                                .withPhase(-1)
                                                .withScale(scale));
                sv.rig = instance;
            } catch (IllegalArgumentException e) {
                log.warn("어항 리그를 띄우지 못했습니다: {} {}", rig.get(), String.valueOf(e.getMessage()));
            }
        }
    }

    private Optional<RigId> rigFor(FishId fish) {
        RigId own = new RigId(new HarborId(fish.id().namespace(), "rig/fish/" + fish.id().name()));
        return api.rigs().ids().contains(own) ? Optional.of(own) : Optional.empty();
    }

    private @Nullable BlockDisplay floor(
            World world, DisplayAnchor a, DisplayCaseRules.Theme theme) {
        BlockData data;
        try {
            data = Bukkit.createBlockData(theme.floor());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return world.spawn(
                new Location(world, a.x(), a.y(), a.z()),
                BlockDisplay.class,
                e -> {
                    e.setPersistent(false);
                    e.setBlock(data);
                    e.setTransformation(
                            new Transformation(
                                    new Vector3f(0.05f, 0.02f, 0.05f),
                                    new AxisAngle4f(),
                                    new Vector3f(0.9f, 0.06f, 0.9f),
                                    new AxisAngle4f()));
                });
    }

    private static @Nullable Particle particle(String name) {
        try {
            return Particle.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 시청자·헤엄

    private void tick() {
        ticks++;
        if (ticks % VIEW_TICKS == 0) {
            for (PlotView view : plots.values()) {
                World world = Bukkit.getWorld(view.plot.world());
                if (world == null) {
                    continue;
                }
                List<Player> viewers = viewers(view, world);
                Set<UUID> ids = ids(viewers);
                if (!ids.equals(view.viewers)) {
                    view.viewers = ids;
                    for (SlotView sv : view.views.values()) {
                        RigInstance rig = sv.rig;
                        if (rig != null) {
                            rig.setViewers(viewers);
                        }
                    }
                }
                particles(view, viewers);
            }
        }
        if (ticks % SWIM_TICKS == 0) {
            double radius = rules().swimRadius();
            for (PlotView view : plots.values()) {
                if (view.viewers.isEmpty()) {
                    continue; // 아무도 보지 않으면 이동 생략
                }
                for (SlotView sv : view.views.values()) {
                    RigInstance rig = sv.rig;
                    if (rig != null
                            && rig.isValid()
                            && ThreadLocalRandom.current().nextInt(3) == 0) {
                        swim(rig, sv.center, radius);
                    }
                }
            }
        }
    }

    private static void swim(RigInstance rig, Location center, double radius) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location to =
                center.clone()
                        .add(
                                r.nextDouble(-radius, radius),
                                r.nextDouble(-radius / 2, radius / 2),
                                r.nextDouble(-radius, radius));
        Location from = rig.location();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz > 1e-4) {
            rig.setHeading((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
        }
        rig.moveTo(to, 25);
    }

    private void particles(PlotView view, List<Player> viewers) {
        for (SlotView sv : view.views.values()) {
            Particle p = sv.particle;
            if (p == null) {
                continue;
            }
            for (Player player : viewers) {
                try {
                    player.spawnParticle(p, sv.center, 2, 0.25, 0.25, 0.25, 0.01);
                } catch (IllegalArgumentException e) {
                    sv.particle = null; // 데이터가 필요한 파티클은 생략
                }
            }
        }
    }

    /** 플롯 안 또는 경계에서 view_radius 블록 안의 플레이어. */
    private List<Player> viewers(PlotView view, World world) {
        double r = rules().viewRadius();
        PlotInfo p = view.plot;
        List<Player> result = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            Location l = Locs.of(player);
            if (l.getX() >= p.minX() - r
                    && l.getX() < p.maxX() + 1 + r
                    && l.getZ() >= p.minZ() - r
                    && l.getZ() < p.maxZ() + 1 + r) {
                result.add(player);
            }
        }
        return result;
    }

    private static Set<UUID> ids(List<Player> players) {
        Set<UUID> ids = new HashSet<>();
        players.forEach(p -> ids.add(p.getUniqueId()));
        return Set.copyOf(ids);
    }

    // ------------------------------------------------------------------ 클릭

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        SlotRef ref = boxes.get(clicked.getUniqueId());
        if (ref == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        event.setCancelled(true);
        PlotView view = plots.get(ref.plotId());
        if (view == null) {
            return;
        }
        Player player = event.getPlayer();
        if (housing.can(player, view.plot, PlotPermission.EDIT_DISPLAY) && !player.isSneaking()) {
            openEditor(player, view, ref.slot());
        } else {
            showInfo(player, view, ref.slot());
        }
    }

    /** 방문객 정보: 이름·★·크기·처음 낚은 날·주인의 서버 순위를 클릭한 사람에게만 몇 초. */
    private void showInfo(Player player, PlotView view, int slot) {
        DisplaySlot row = view.rows.get(slot);
        SlotView sv = view.views.get(slot);
        if (row == null || sv == null || !DisplayCaseRules.slotOpen(slot, view.slotCount)) {
            api.messages().send(player, "collection.display.empty");
            return;
        }
        SeasonId season = api.infra().season();
        UUID owner = view.plot.owner();
        var _ =
                api.database()
                        .async(c -> CollectionRepository.rank(c, row.fish(), season, "all", owner))
                        .exceptionally(e -> 0)
                        .thenAcceptAsync(rank -> info(player, view, sv, row, rank), tasks.main());
    }

    private void info(Player player, PlotView view, SlotView sv, DisplaySlot row, int rank) {
        if (!player.isOnline()) {
            return;
        }
        MessageService msg = api.messages();
        String name =
                api.content()
                        .snapshot()
                        .get(FishDef.TYPE, row.fish().id())
                        .map(FishDef::name)
                        .orElse(row.fish().id().name());
        List<Component> lines = new ArrayList<>();
        lines.add(
                msg.get(
                        "collection.display.info_name",
                        Placeholder.unparsed("name", name),
                        Placeholder.unparsed("owner", view.plot.ownerName())));
        lines.add(
                msg.get(
                        "collection.display.info_size",
                        Placeholder.unparsed("size", CollectionManager.fmt(row.size())),
                        Placeholder.unparsed(
                                "quality", Quality.of(Math.clamp(row.quality(), 1, 5)).display())));
        lines.add(
                msg.get(
                        "collection.display.info_date",
                        Placeholder.unparsed(
                                "date",
                                DATE.withZone(api.time().zone()).format(row.firstCaught()))));
        lines.add(
                rank > 0
                        ? msg.get(
                                "collection.display.info_rank",
                                Placeholder.unparsed("rank", String.valueOf(rank)))
                        : msg.get("collection.display.info_no_rank"));
        World world = Bukkit.getWorld(view.plot.world());
        if (world == null) {
            return;
        }
        Location at = sv.center.clone().add(0, 0.9, 0);
        Component text = Component.join(JoinConfiguration.newlines(), lines);
        TextDisplay display =
                world.spawn(
                        at,
                        TextDisplay.class,
                        e -> {
                            e.setPersistent(false);
                            e.setVisibleByDefault(false);
                            e.setBillboard(Display.Billboard.CENTER);
                            e.text(text);
                        });
        player.showEntity(plugin, display);
        var _ = tasks.later(rules().infoSeconds() * 20L, display::remove);
    }

    // ------------------------------------------------------------------ 편집 화면

    private void openEditor(Player player, PlotView view, int slot) {
        MessageService msg = api.messages();
        if (!DisplayCaseRules.slotOpen(slot, view.slotCount)) {
            api.messages()
                    .send(
                            player,
                            "collection.display.slot_locked",
                            Placeholder.unparsed("count", String.valueOf(view.slotCount)));
            return;
        }
        long plotId = view.plot.plotId();
        Menu menu =
                new Menu(
                        msg.get(
                                "collection.display.menu_title",
                                Placeholder.unparsed("slot", String.valueOf(slot + 1)),
                                Placeholder.unparsed("count", String.valueOf(view.slotCount))),
                        3);
        menu.button(
                11,
                named(Material.TROPICAL_FISH, msg.get("collection.display.menu_fish")),
                e -> openFishPicker(player, view, slot));
        menu.button(
                13,
                named(
                        Material.PRISMARINE_CRYSTALS,
                        msg.get(
                                "collection.display.menu_theme",
                                Placeholder.unparsed("slots", String.valueOf(view.themeSlots)))),
                e -> openThemePicker(player, view, slot));
        menu.button(
                15,
                named(Material.BARRIER, msg.get("collection.display.menu_clear")),
                e -> {
                    player.closeInventory();
                    var _ = clear(player, plotId, slot);
                });
        menu.open(player);
    }

    private void openFishPicker(Player player, PlotView view, int slot) {
        long plotId = view.plot.plotId();
        var _ =
                ownerCodex(view.plot.owner())
                        .thenAccept(
                                codex -> {
                                    if (!player.isOnline()) {
                                        return;
                                    }
                                    List<FishDef> fish = new ArrayList<>();
                                    for (FishId id : codex.keySet()) {
                                        api.content()
                                                .snapshot()
                                                .get(FishDef.TYPE, id.id())
                                                .filter(f -> !f.releaseOnly())
                                                .ifPresent(fish::add);
                                    }
                                    if (fish.isEmpty()) {
                                        api.messages().send(player, "collection.display.no_codex");
                                        return;
                                    }
                                    fish.sort(Comparator.comparingInt(FishDef::no));
                                    MessageService msg = api.messages();
                                    new PagedMenu<>(
                                                    msg.get("collection.display.pick_title"),
                                                    6,
                                                    fish,
                                                    f ->
                                                            new PagedMenu.Entry(
                                                                    icon(f, codex.get(f.id())),
                                                                    e -> {
                                                                        player.closeInventory();
                                                                        var _ =
                                                                                set(
                                                                                        player,
                                                                                        plotId,
                                                                                        slot,
                                                                                        f.id());
                                                                    }),
                                                    named(
                                                            Material.ARROW,
                                                            msg.get("collection.menu.previous")),
                                                    named(
                                                            Material.ARROW,
                                                            msg.get("collection.menu.next")))
                                            .show(player, 0);
                                });
    }

    private void openThemePicker(Player player, PlotView view, int slot) {
        if (!view.rows.containsKey(slot)) {
            api.messages().send(player, "collection.display.empty");
            return;
        }
        DisplayCaseRules rules = rules();
        MessageService msg = api.messages();
        UUID owner = view.plot.owner();
        List<DisplayCaseRules.Theme> options = new ArrayList<>();
        options.add(rules.defaultTheme());
        for (DisplayCaseRules.Theme t : rules.themes()) {
            HarborId cosmetic = t.cosmetic();
            if (cosmetic != null && themes.owns(owner, cosmetic)) {
                options.add(t);
            }
        }
        long plotId = view.plot.plotId();
        Menu menu = new Menu(msg.get("collection.display.theme_title"), 3);
        for (int i = 0; i < options.size() && i < 27; i++) {
            DisplayCaseRules.Theme t = options.get(i);
            HarborId cosmetic = t.cosmetic();
            Material icon =
                    Optional.ofNullable(Material.matchMaterial(t.floor()))
                            .filter(Material::isItem)
                            .orElse(Material.SAND);
            Component label =
                    cosmetic == null
                            ? msg.get("collection.display.theme_default")
                            : msg.get(
                                    "collection.display.theme_name",
                                    Placeholder.unparsed("id", cosmetic.name()));
            menu.button(
                    i,
                    named(icon, label),
                    e -> {
                        player.closeInventory();
                        var _ = setTheme(player, plotId, slot, cosmetic);
                    });
        }
        menu.open(player);
    }

    private ItemStack icon(FishDef fish, @Nullable CodexEntry entry) {
        String baseItem =
                api.content()
                        .snapshot()
                        .single(Balance.TYPE)
                        .string("items.fish_base_item")
                        .orElse("minecraft:cod");
        Material material =
                Optional.ofNullable(Material.matchMaterial(baseItem)).orElse(Material.COD);
        ItemStack stack = ItemStack.of(material);
        try {
            stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(fish.model()));
        } catch (IllegalArgumentException e) {
            // 모델 키 형식이 틀리면 기본 모양
        }
        stack.setData(
                DataComponentTypes.ITEM_NAME,
                api.messages()
                        .get(
                                "item.name." + fish.grade().key(),
                                Placeholder.unparsed("name", fish.name())));
        if (entry != null) {
            stack.setData(
                    DataComponentTypes.LORE,
                    ItemLore.lore(
                            List.of(
                                    api.messages()
                                            .get(
                                                    "collection.display.pick_lore",
                                                    Placeholder.unparsed(
                                                            "size",
                                                            CollectionManager.fmt(entry.maxSize())),
                                                    Placeholder.unparsed(
                                                            "quality",
                                                            Quality.of(
                                                                            Math.clamp(
                                                                                    entry
                                                                                            .bestQuality(),
                                                                                    1,
                                                                                    5))
                                                                    .display()))
                                            .decorationIfAbsent(
                                                    TextDecoration.ITALIC,
                                                    TextDecoration.State.FALSE))));
        }
        return stack;
    }

    private static ItemStack named(Material material, Component name) {
        ItemStack stack = ItemStack.of(material);
        stack.setData(DataComponentTypes.ITEM_NAME, name);
        return stack;
    }

    /** 플롯 주인의 누적 도감 (접속 중이면 메모리, 아니면 DB). 메인 스레드에서 완료. */
    private CompletableFuture<Map<FishId, CodexEntry>> ownerCodex(UUID owner) {
        Optional<HarborPlayer> online = api.players().get(owner);
        if (online.isPresent()) {
            return CompletableFuture.completedFuture(
                    online.get().get(CollectionManager.COMPONENT).total());
        }
        SeasonId season = api.infra().season();
        return api.database()
                .async(c -> CollectionRepository.load(c, owner, season).total())
                .thenApplyAsync(Map::copyOf, tasks.main());
    }

    // ------------------------------------------------------------------ 편집

    @Override
    public CompletableFuture<Boolean> set(Player editor, long plotId, int slot, FishId fish) {
        PlotView view = plots.get(plotId);
        if (view == null || !housing.can(editor, view.plot, PlotPermission.EDIT_DISPLAY)) {
            api.messages().send(editor, "collection.display.no_permission");
            return CompletableFuture.completedFuture(false);
        }
        if (!DisplayCaseRules.slotOpen(slot, view.slotCount) || !hasAnchor(plotId, slot)) {
            api.messages()
                    .send(
                            editor,
                            "collection.display.slot_locked",
                            Placeholder.unparsed("count", String.valueOf(view.slotCount)));
            return CompletableFuture.completedFuture(false);
        }
        UUID owner = view.plot.owner();
        return ownerCodex(owner)
                .thenCompose(
                        codex -> {
                            CodexEntry entry = codex.get(fish);
                            if (entry == null) {
                                api.messages().send(editor, "collection.display.not_registered");
                                return CompletableFuture.completedFuture(false);
                            }
                            DisplaySlot old = view.rows.get(slot);
                            DisplaySlot row =
                                    new DisplaySlot(
                                            slot,
                                            fish,
                                            entry.maxSize(),
                                            entry.bestQuality(),
                                            entry.firstCaught(),
                                            old == null ? null : old.theme());
                            return api.database()
                                    .asyncVoid(c -> DisplayRepository.upsert(c, plotId, owner, row))
                                    .thenApplyAsync(
                                            v -> {
                                                PlotView now = plots.get(plotId);
                                                if (now != null) {
                                                    now.rows.put(slot, row);
                                                    render(now);
                                                }
                                                api.messages()
                                                        .send(editor, "collection.display.set");
                                                return true;
                                            },
                                            tasks.main());
                        });
    }

    @Override
    public CompletableFuture<Boolean> setTheme(
            Player editor, long plotId, int slot, @Nullable HarborId theme) {
        PlotView view = plots.get(plotId);
        if (view == null || !housing.can(editor, view.plot, PlotPermission.EDIT_DISPLAY)) {
            api.messages().send(editor, "collection.display.no_permission");
            return CompletableFuture.completedFuture(false);
        }
        DisplaySlot row = view.rows.get(slot);
        if (row == null) {
            api.messages().send(editor, "collection.display.empty");
            return CompletableFuture.completedFuture(false);
        }
        if (theme != null) {
            if (rules().theme(theme).isEmpty() || !themes.owns(view.plot.owner(), theme)) {
                api.messages().send(editor, "collection.display.theme_not_owned");
                return CompletableFuture.completedFuture(false);
            }
            long others =
                    view.rows.values().stream()
                            .filter(r -> r.slot() != slot && r.theme() != null)
                            .count();
            if (others >= view.themeSlots) {
                api.messages()
                        .send(
                                editor,
                                "collection.display.theme_full",
                                Placeholder.unparsed("slots", String.valueOf(view.themeSlots)));
                return CompletableFuture.completedFuture(false);
            }
        }
        return api.database()
                .async(c -> DisplayRepository.setTheme(c, plotId, slot, theme))
                .thenApplyAsync(
                        ok -> {
                            PlotView now = plots.get(plotId);
                            DisplaySlot current = now == null ? null : now.rows.get(slot);
                            if (ok && now != null && current != null) {
                                now.rows.put(slot, withTheme(current, theme));
                                render(now);
                            }
                            api.messages()
                                    .send(
                                            editor,
                                            ok
                                                    ? "collection.display.theme_set"
                                                    : "collection.admin.failed");
                            return ok;
                        },
                        tasks.main());
    }

    @Override
    public CompletableFuture<Boolean> clear(@Nullable Player editor, long plotId, int slot) {
        PlotView view = plots.get(plotId);
        if (editor != null
                && (view == null || !housing.can(editor, view.plot, PlotPermission.EDIT_DISPLAY))) {
            api.messages().send(editor, "collection.display.no_permission");
            return CompletableFuture.completedFuture(false);
        }
        return api.database()
                .async(c -> DisplayRepository.delete(c, plotId, slot))
                .thenApplyAsync(
                        ok -> {
                            PlotView now = plots.get(plotId);
                            if (now != null && now.rows.remove(slot) != null) {
                                render(now);
                            }
                            if (editor != null) {
                                api.messages().send(editor, "collection.display.cleared");
                            }
                            return ok;
                        },
                        tasks.main());
    }

    private boolean hasAnchor(long plotId, int slot) {
        return housing.displayAnchors(plotId).stream().anyMatch(a -> a.slot() == slot);
    }

    private static DisplaySlot withTheme(DisplaySlot r, @Nullable HarborId theme) {
        return new DisplaySlot(r.slot(), r.fish(), r.size(), r.quality(), r.firstCaught(), theme);
    }
}
