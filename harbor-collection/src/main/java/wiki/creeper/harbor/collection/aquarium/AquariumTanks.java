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
import wiki.creeper.harbor.common.rig.FishRigs;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.core.task.Tasks;

/**
 * Harbor aquarium tank display (docs 20 4.4 "tank display" · "tank structures"). Runs only on the
 * main server (the server that has aquarium.yml display.world).
 *
 * <ul>
 *   <li>Each donated species gets one Core rig swimming in a tank. Rigs are hidden by default and
 *       visible only to players inside the aquarium view box.
 *   <li>visible_max rigs are shown at once; any more rotate every rotate_minutes ({@link
 *       TankLayout}).
 *   <li>Tanks with no viewer within lod_radius skip swim movement (animation LOD is Core
 *       rig.lod_distance).
 *   <li>A structure template is placed per stage. On startup and on stage-up, checks whether it
 *       matches the current stage and places it if not (idempotent). Players inside the area are
 *       moved to the entrance just before placing. Structure files are read on a virtual thread;
 *       placement happens on the main thread.
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

    /** One swimming rig. */
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

    /** When donated species or the stage change (after AquariumManager.refresh, main thread). */
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

    /** Content reload: redraws from scratch. */
    public void reload() {
        clear();
        appliedStructure = null;
        update(species, stage);
    }

    // ------------------------------------------------------------------ layout

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
        // Remove species that are gone or whose tank changed
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
                                                .withPhase(-1)
                                                .withScale(tankScale(e.getKey(), rig.get(), tank)));
                instance.setViewers(watching);
                swimmers.put(e.getKey(), new Swimmer(instance, tank));
            } catch (IllegalArgumentException ex) {
                log.warn("수조 리그를 띄우지 못했습니다: {} {}", rig.get(), String.valueOf(ex.getMessage()));
            }
        }
        drawLabels(world, open, layout);
    }

    /**
     * Draws the species rig at the species size (middle of size_cm), shrunk so it does not exceed
     * half the tank width (resource pack species models are larger than life size). 1 if the rig
     * has no body length.
     */
    private double tankScale(FishId fish, RigId rig, AquariumRules.Tank tank) {
        Optional<RigDef> def = api.rigs().definition(rig);
        if (def.isEmpty() || def.get().length() <= 0) {
            return 1.0;
        }
        Box box = tank.box();
        double room = Math.min(box.maxX() - box.minX(), box.maxZ() - box.minZ()) / 2;
        double length =
                api.content()
                        .snapshot()
                        .get(FishDef.TYPE, fish.id())
                        .map(FishRigs::naturalLength)
                        .orElse(def.get().length() * def.get().scale());
        return FishRigs.fitScale(def.get(), room > 0 ? Math.min(length, room) : length);
    }

    /**
     * Species rig rig/fish/&lt;model name&gt; (FishRigs; species ID name if absent) → otherwise
     * fallback_rig.
     */
    private Optional<RigId> rigFor(FishId fish, AquariumRules.Display d) {
        Optional<FishDef> def = api.content().snapshot().get(FishDef.TYPE, fish.id());
        if (def.isPresent()) {
            RigId own = FishRigs.rigOf(def.get());
            if (api.rigs().ids().contains(own)) {
                return Optional.of(own);
            }
        }
        RigId byId = new RigId(new HarborId(fish.id().namespace(), "rig/fish/" + fish.id().name()));
        if (api.rigs().ids().contains(byId)) {
            return Optional.of(byId);
        }
        RigId fallback = d.fallbackRig();
        return fallback != null && api.rigs().ids().contains(fallback)
                ? Optional.of(fallback)
                : Optional.empty();
    }

    /**
     * Tank name tag: species in the tank and their first donor (a species donated for the first
     * time has the donor's name engraved on the tag).
     */
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

    // ------------------------------------------------------------------ periodic task

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
        // Rotation (only meaningful beyond visible_max)
        if (ticks % (d.rotateMinutes() * 60L * 20L) < TICK && species.size() > d.visibleMax()) {
            rotation++;
            relayout();
        }
        if (ticks % 20 < TICK) {
            updateViewers(world);
            // Fill in rigs and name tags missed because chunks loaded late (once every 10 seconds)
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

    /** Viewers: players inside the view box. Shown on entry, hidden on exit. */
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

    // ------------------------------------------------------------------ structures

    /**
     * Places the current stage structure (unchanged if already placed). The file is read on a
     * virtual thread.
     */
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
