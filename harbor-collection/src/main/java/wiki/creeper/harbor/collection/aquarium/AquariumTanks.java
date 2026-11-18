package wiki.creeper.harbor.collection.aquarium;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.structure.Structure;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.content.AquariumRules;
import wiki.creeper.harbor.collection.content.Box;
import wiki.creeper.harbor.collection.content.Point;
import wiki.creeper.harbor.collection.data.CollectionRepository.SpeciesRow;
import wiki.creeper.harbor.collection.service.Locs;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * 항구 수족관 수조 연출 (docs 20 4.4 '수조 연출'·'수조 구조물'). 본 서버(aquarium.yml display.world가 있는 서버)에서만 돈다.
 *
 * <ul>
 *   <li>기부된 어종마다 Core 리그 1개가 수조 안을 헤엄친다. 리그는 기본 숨김이고 수족관 view 상자 안 플레이어에게만 보인다.
 *   <li>동시에 띄우는 리그는 visible_max개, 넘치면 rotate_minutes마다 교대한다 ({@link TankLayout}).
 *   <li>lod_radius 안에 시청자가 없는 수조는 헤엄 이동을 생략한다 (애니메이션 LOD는 Core rig.lod_distance).
 *   <li>단계마다 구조물 템플릿을 붙인다. 시작할 때와 단계가 오를 때 지금 단계와 맞는지 보고 다르면 붙인다 (멱등). 붙이기 직전 영역 안 플레이어는 입구로 옮긴다.
 *       구조물 파일 읽기는 가상 스레드, 붙이기는 메인 스레드.
 * </ul>
 */
public final class AquariumTanks {

    private static final int TICK = 5;
    private static final double MARGIN = 0.3;

    private final HarborApi api;
    private final Tasks tasks;
    private final Logger log;
    private final Map<FishId, Swimmer> swimmers = new LinkedHashMap<>();
    private final Map<String, TextDisplay> labels = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private List<SpeciesRow> species = List.of();
    private int stage;
    private long rotation;
    private long ticks;
    private @Nullable String appliedStructure;
    private @Nullable BukkitTask task;

    /** 헤엄치는 리그 하나. */
    private static final class Swimmer {
        final RigInstance rig;
        final AquariumRules.Tank tank;
        long nextMove;

        Swimmer(RigInstance rig, AquariumRules.Tank tank) {
            this.rig = rig;
            this.tank = tank;
        }
    }

    public AquariumTanks(HarborApi api, Tasks tasks, Logger log) {
        this.api = api;
        this.tasks = tasks;
        this.log = log;
    }

    private AquariumRules.Display display() {
        return api.content().snapshot().single(AquariumRules.TYPE).display();
    }

    private @Nullable World world() {
        AquariumRules.Display d = display();
        return d.enabled() ? Bukkit.getWorld(d.world()) : null;
    }

    public void start() {
        task = tasks.timer(20, TICK, this::tick);
    }

    public void stop() {
        BukkitTask t = task;
        if (t != null) {
            t.cancel();
        }
        clear();
    }

    /** 기부 어종·단계가 바뀌었을 때 (AquariumManager.refresh 뒤, 메인 스레드). */
    public void update(List<SpeciesRow> newSpecies, int newStage) {
        species = List.copyOf(newSpecies);
        boolean stageChanged = newStage != stage;
        stage = newStage;
        if (world() == null) {
            clear();
            return;
        }
        if (stageChanged || appliedStructure == null) {
            applyStructure();
        }
        relayout();
    }

    /** 콘텐츠 리로드: 처음부터 다시 그린다. */
    public void reload() {
        clear();
        appliedStructure = null;
        update(species, stage);
    }

    // ------------------------------------------------------------------ 배치

    private void relayout() {
        World world = world();
        if (world == null) {
            return;
        }
        AquariumRules.Display d = display();
        List<AquariumRules.Tank> open = d.openTanks(stage);
        Set<FishId> deep = new HashSet<>();
        List<FishId> ids = new ArrayList<>();
        for (SpeciesRow row : species) {
            ids.add(row.fish());
            api.content()
                    .snapshot()
                    .get(FishDef.TYPE, row.fish().id())
                    .filter(f -> d.deepRegions().contains(f.region()))
                    .ifPresent(f -> deep.add(f.id()));
        }
        Map<FishId, String> layout = TankLayout.assign(ids, open, deep, d.visibleMax(), rotation);
        // 빠진 어종·수조가 바뀐 어종은 지운다
        swimmers.entrySet()
                .removeIf(
                        e -> {
                            String tank = layout.get(e.getKey());
                            if (tank == null
                                    || !tank.equals(e.getValue().tank.id())
                                    || !e.getValue().rig.isValid()) {
                                e.getValue().rig.remove();
                                return true;
                            }
                            return false;
                        });
        Map<String, AquariumRules.Tank> tanks = new HashMap<>();
        open.forEach(t -> tanks.put(t.id(), t));
        List<Player> watching = watching(world);
        for (Map.Entry<FishId, String> e : layout.entrySet()) {
            if (swimmers.containsKey(e.getKey())) {
                continue;
            }
            AquariumRules.Tank tank = tanks.get(e.getValue());
            Optional<RigId> rig = rigFor(e.getKey(), d);
            if (tank == null || rig.isEmpty()) {
                continue;
            }
            Point p = tank.box().lerp(random(), random(), random(), MARGIN);
            Location at = new Location(world, p.x(), p.y(), p.z(), random() * 360f, 0);
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                continue;
            }
            try {
                RigInstance instance =
                        api.rigs()
                                .spawn(
                                        rig.get(),
                                        at,
                                        RigOptions.privateTo(List.<Player>of())
                                                .withAnimation("swim")
                                                .withPhase(-1));
                instance.setViewers(watching);
                swimmers.put(e.getKey(), new Swimmer(instance, tank));
            } catch (IllegalArgumentException ex) {
                log.warn("수조 리그를 띄우지 못했습니다: {} {}", rig.get(), String.valueOf(ex.getMessage()));
            }
        }
        drawLabels(world, open, layout);
    }

    /** 어종 리그 rig/fish/<이름> → 없으면 fallback_rig. */
    private Optional<RigId> rigFor(FishId fish, AquariumRules.Display d) {
        RigId own = new RigId(new HarborId(fish.id().namespace(), "rig/fish/" + fish.id().name()));
        if (api.rigs().ids().contains(own)) {
            return Optional.of(own);
        }
        RigId fallback = d.fallbackRig();
        return fallback != null && api.rigs().ids().contains(fallback)
                ? Optional.of(fallback)
                : Optional.empty();
    }

    /** 수조 이름표: 수조에 있는 어종과 처음 기부한 사람 (처음 기부되는 어종은 이름표에 기부자 이름을 새긴다). */
    private void drawLabels(
            World world, List<AquariumRules.Tank> open, Map<FishId, String> layout) {
        labels.values().forEach(TextDisplay::remove);
        labels.clear();
        Map<FishId, SpeciesRow> rows = new HashMap<>();
        species.forEach(r -> rows.put(r.fish(), r));
        for (AquariumRules.Tank tank : open) {
            List<Component> lines = new ArrayList<>();
            lines.add(
                    api.messages()
                            .get(
                                    "collection.aquarium.tank_label",
                                    Placeholder.unparsed("tank", tank.id())));
            layout.forEach(
                    (fish, tankId) -> {
                        SpeciesRow row = rows.get(fish);
                        if (!tankId.equals(tank.id()) || row == null) {
                            return;
                        }
                        String name =
                                api.content()
                                        .snapshot()
                                        .get(FishDef.TYPE, fish.id())
                                        .map(FishDef::name)
                                        .orElse(fish.id().name());
                        lines.add(
                                api.messages()
                                        .get(
                                                "collection.aquarium.tank_line",
                                                Placeholder.unparsed("name", name),
                                                Placeholder.unparsed("donor", row.donor())));
                    });
            Point label = tank.label();
            Box box = tank.box();
            Location at =
                    label != null
                            ? new Location(world, label.x(), label.y(), label.z())
                            : new Location(world, box.centerX(), box.maxY() + 1.4, box.centerZ());
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                continue;
            }
            Component text =
                    Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), lines);
            TextDisplay display =
                    world.spawn(
                            at,
                            TextDisplay.class,
                            e -> {
                                e.setPersistent(false);
                                e.setBillboard(Display.Billboard.CENTER);
                                e.text(text);
                            });
            labels.put(tank.id(), display);
        }
    }

    private void clear() {
        swimmers.values().forEach(s -> s.rig.remove());
        swimmers.clear();
        labels.values().forEach(TextDisplay::remove);
        labels.clear();
        viewers.clear();
    }

    // ------------------------------------------------------------------ 주기 작업

    private void tick() {
        World world = world();
        if (world == null) {
            if (!swimmers.isEmpty()) {
                clear();
            }
            return;
        }
        ticks += TICK;
        AquariumRules.Display d = display();
        // 교대 (visible_max를 넘을 때만 의미가 있다)
        if (ticks % (d.rotateMinutes() * 60L * 20L) < TICK && species.size() > d.visibleMax()) {
            rotation++;
            relayout();
        }
        if (ticks % 20 < TICK) {
            updateViewers(world);
            // 청크가 늦게 로드되어 빠진 리그·이름표를 채운다 (10초에 한 번)
            boolean missing =
                    (!viewers.isEmpty()
                                    && swimmers.size() < Math.min(species.size(), d.visibleMax()))
                            || labels.size() < d.openTanks(stage).size();
            if (ticks % 200 < TICK && missing) {
                relayout();
            }
        }
        if (viewers.isEmpty()) {
            return;
        }
        List<Player> watching = watching(world);
        for (Swimmer s : swimmers.values()) {
            if (!s.rig.isValid() || ticks < s.nextMove || !near(watching, s.tank, d.lodRadius())) {
                continue;
            }
            swim(s, world, d.swimSpeed());
        }
    }

    private void swim(Swimmer s, World world, double speed) {
        Point p = s.tank.box().lerp(random(), random(), random(), MARGIN);
        Location from = s.rig.location();
        double dx = p.x() - from.getX();
        double dy = p.y() - from.getY();
        double dz = p.z() - from.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int moveTicks = (int) Math.clamp(dist / Math.max(0.01, speed), 10, 59);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.max(1e-3, Math.hypot(dx, dz))));
        s.rig.setHeading(yaw, Math.clamp(pitch, -30f, 30f));
        s.rig.moveTo(new Location(world, p.x(), p.y(), p.z(), yaw, 0), moveTicks);
        s.nextMove = ticks + moveTicks + ThreadLocalRandom.current().nextInt(0, 20);
    }

    /** 시청자: view 상자 안 플레이어. 들어오면 보이게, 나가면 숨긴다. */
    private void updateViewers(World world) {
        List<Player> now = watching(world);
        Set<UUID> ids = new HashSet<>();
        now.forEach(p -> ids.add(p.getUniqueId()));
        if (ids.equals(viewers)) {
            return;
        }
        viewers.clear();
        viewers.addAll(ids);
        for (Swimmer s : swimmers.values()) {
            s.rig.setViewers(now);
        }
    }

    private List<Player> watching(World world) {
        Box view = display().view();
        List<Player> result = new ArrayList<>();
        for (Player p : world.getPlayers()) {
            Location l = Locs.of(p);
            if (view.isEmpty() || view.contains(l.getX(), l.getY(), l.getZ())) {
                result.add(p);
            }
        }
        return result;
    }

    private static boolean near(List<Player> players, AquariumRules.Tank tank, double radius) {
        Box b = tank.box();
        double r2 = radius * radius;
        for (Player p : players) {
            Location l = Locs.of(p);
            double dx = l.getX() - b.centerX();
            double dy = l.getY() - b.centerY();
            double dz = l.getZ() - b.centerZ();
            if (dx * dx + dy * dy + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    private static float random() {
        return ThreadLocalRandom.current().nextFloat();
    }

    // ------------------------------------------------------------------ 구조물

    /** 지금 단계 구조물을 붙인다 (이미 붙였으면 그대로). 파일은 가상 스레드에서 읽는다. */
    private void applyStructure() {
        AquariumRules rules = api.content().snapshot().single(AquariumRules.TYPE);
        Optional<String> key = rules.structureFor(stage);
        Point origin = rules.display().structureOrigin();
        World world = world();
        if (key.isEmpty() || origin == null || world == null) {
            return;
        }
        if (key.get().equals(appliedStructure)) {
            return;
        }
        NamespacedKey nk = NamespacedKey.fromString(key.get());
        if (nk == null) {
            log.warn("수족관 구조물 키 형식이 틀렸습니다: {}", key.get());
            return;
        }
        String target = key.get();
        appliedStructure = target;
        File file = Bukkit.getStructureManager().getStructureFile(nk);
        var _ =
                CompletableFuture.supplyAsync(() -> read(file), tasks.virtual())
                        .thenAcceptAsync(
                                structure -> {
                                    if (structure == null) {
                                        log.warn("수족관 구조물 파일이 없습니다: {} ({})", target, file);
                                        return;
                                    }
                                    place(world, origin, structure, rules.display());
                                    log.info("수족관 구조물 {} (단계 {})", target, stage);
                                },
                                tasks.main())
                        .exceptionally(
                                e -> {
                                    log.error("수족관 구조물을 붙이지 못했습니다: {}", target, e);
                                    return null;
                                });
    }

    private static @Nullable Structure read(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            return Bukkit.getStructureManager().loadStructure(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void place(World world, Point origin, Structure structure, AquariumRules.Display d) {
        Point entrance = d.entrance();
        Box area = d.structureBox();
        if (entrance != null && !area.isEmpty()) {
            for (Player p : world.getPlayers()) {
                Location l = Locs.of(p);
                if (area.contains(l.getX(), l.getY(), l.getZ())) {
                    var _ =
                            p.teleportAsync(
                                    new Location(
                                            world,
                                            entrance.x(),
                                            entrance.y(),
                                            entrance.z(),
                                            l.getYaw(),
                                            l.getPitch()));
                }
            }
        }
        structure.place(
                new Location(world, origin.x(), origin.y(), origin.z()),
                false,
                StructureRotation.NONE,
                Mirror.NONE,
                0,
                1f,
                new Random());
    }
}
