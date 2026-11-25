package wiki.creeper.harbor.collection.display;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import wiki.creeper.harbor.collection.api.DisplaySlot;
import wiki.creeper.harbor.collection.content.DisplayCaseRules;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.PlotView;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.SlotRef;
import wiki.creeper.harbor.collection.display.DisplayCaseManager.SlotView;
import wiki.creeper.harbor.collection.service.Locs;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.FishId;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;
import wiki.creeper.harbor.common.rig.FishRigs;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.PlotInfo;
import wiki.creeper.harbor.world.api.TankBounds;

/**
 * Spawns and removes the entities of a personal aquarium plot: rigs, floors, particles and
 * swimming.
 */
final class DisplayCaseRenderer {

    private final DisplayCaseManager manager;
    private final HarborApi api;
    private final Logger log;
    private final HousingService housing;
    private final Map<UUID, SlotRef> boxes;

    DisplayCaseRenderer(
            DisplayCaseManager manager,
            HarborApi api,
            Logger log,
            HousingService housing,
            Map<UUID, SlotRef> boxes) {
        this.manager = manager;
        this.api = api;
        this.log = log;
        this.housing = housing;
        this.boxes = boxes;
    }

    void despawn(PlotView view) {
        view.views
                .values()
                .forEach(
                        v -> {
                            boxes.remove(v.box.getUniqueId());
                            v.boxes.forEach(b -> boxes.remove(b.getUniqueId()));
                            v.remove();
                        });
        view.views.clear();
    }

    /** Redraws every slot of the plot. */
    void render(PlotView view) {
        despawn(view);
        World world = Bukkit.getWorld(view.plot.world());
        if (world == null) {
            return;
        }
        DisplayCaseRules rules = manager.rules();
        List<DisplayAnchor> anchors = new ArrayList<>(housing.displayAnchors(view.plot.plotId()));
        anchors.sort(Comparator.comparingInt(DisplayAnchor::slot));
        // Part budget: in slot order use the real rig -> over budget, the simple rig -> still
        // over, show nothing
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
        // Anchors with the same box are one tank (several fish in a larger tank)
        Map<TankBounds, List<DisplayAnchor>> tanks = new LinkedHashMap<>();
        for (DisplayAnchor a : anchors) {
            if (a.world().equals(world.getName()) && world.isChunkLoaded(a.x() >> 4, a.z() >> 4)) {
                tanks.computeIfAbsent(housing.tankOf(a), k -> new ArrayList<>()).add(a);
            }
        }
        double radius = rules.swimRadius();
        for (Map.Entry<TankBounds, List<DisplayAnchor>> tank : tanks.entrySet()) {
            TankBounds b = tank.getKey();
            List<DisplayAnchor> members = tank.getValue();
            List<Integer> slots = members.stream().map(DisplayAnchor::slot).sorted().toList();
            long plotId = view.plot.plotId();
            List<Interaction> tankBoxes = new ArrayList<>();
            for (int x = b.minX(); x <= b.maxX(); x++) {
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    Interaction box =
                            world.spawn(
                                    new Location(world, x + 0.5, b.minY(), z + 0.5),
                                    Interaction.class,
                                    e -> {
                                        e.setPersistent(false);
                                        e.setInteractionWidth(1.0f);
                                        e.setInteractionHeight(b.sizeY());
                                        e.setResponsive(true);
                                    });
                    tankBoxes.add(box);
                    boxes.put(box.getUniqueId(), new SlotRef(plotId, slots.getFirst()));
                }
            }
            Location center =
                    new Location(
                            world,
                            (b.minX() + b.maxX() + 1) / 2.0,
                            (b.minY() + b.maxY() + 1) / 2.0,
                            (b.minZ() + b.maxZ() + 1) / 2.0);
            Location label = new Location(world, center.getX(), b.maxY() + 1.4, center.getZ());
            double rx = b.single() ? radius : Math.max(radius, b.sizeX() / 2.0 - 0.35);
            double ry = b.single() ? radius / 2 : Math.max(radius / 2, b.sizeY() / 2.0 - 0.3);
            double rz = b.single() ? radius : Math.max(radius, b.sizeZ() / 2.0 - 0.35);
            // Room for one fish: 0.7 of a block in a 1×1 tank, more across a wider tank
            double room = 0.7 * Math.min(b.sizeX(), b.sizeZ());
            boolean floored = false;
            for (DisplayAnchor a : members) {
                SlotView sv = new SlotView(center, tankBoxes.getFirst(), b, label, rx, ry, rz);
                sv.mates = slots;
                if (a.slot() == slots.getFirst()) {
                    sv.boxes = List.copyOf(tankBoxes);
                    // The tank's own model goes with the first slot, filled or empty
                    ItemDisplay model = tankModel(world, b, a);
                    if (model != null) {
                        sv.props.add(model);
                    }
                }
                view.views.put(a.slot(), sv);
                DisplaySlot row = view.rows.get(a.slot());
                if (row == null || !DisplayCaseRules.slotOpen(a.slot(), view.slotCount)) {
                    continue;
                }
                DisplayCaseRules.Theme theme =
                        rules.theme(row.theme()).orElse(rules.defaultTheme());
                if (!floored) {
                    BlockDisplay floor = floor(world, b, theme);
                    if (floor != null) {
                        sv.props.add(floor);
                    }
                    floored = true;
                }
                sv.particle = theme.particle() == null ? null : particle(theme.particle());
                spawnFish(sv, a, row, plan, simple, hasSimple, room, viewers);
            }
        }
    }

    /** The fish of one slot: real rig, simple rig or nothing by the part budget plan. */
    private void spawnFish(
            SlotView sv,
            DisplayAnchor a,
            DisplaySlot row,
            Map<Integer, Integer> plan,
            @Nullable RigId simple,
            boolean hasSimple,
            double room,
            List<Player> viewers) {
        int mode = plan.getOrDefault(a.slot(), 2);
        Optional<RigId> rig =
                mode == 0
                        ? rigFor(row.fish())
                        : mode == 1 ? Optional.ofNullable(simple) : Optional.empty();
        if (rig.isEmpty() && mode == 0 && hasSimple) {
            rig = Optional.ofNullable(simple);
        }
        if (rig.isEmpty()) {
            return;
        }
        double length =
                api.rigs()
                        .definition(rig.get())
                        .map(r -> r.length() * r.scale())
                        .filter(l -> l > 0)
                        .orElse(0.5);
        double scale = Math.clamp(room / Math.max(0.1, length), 0.2, 1.0);
        Location at = new Location(sv.center.getWorld(), a.x() + 0.5, a.y() + 0.5, a.z() + 0.5);
        try {
            sv.rig =
                    api.rigs()
                            .spawn(
                                    rig.get(),
                                    at,
                                    RigOptions.privateTo(viewers)
                                            .withAnimation("swim")
                                            .withPhase(-1)
                                            .withScale(scale));
        } catch (IllegalArgumentException e) {
            log.warn("어항 리그를 띄우지 못했습니다: {} {}", rig.get(), String.valueOf(e.getMessage()));
        }
    }

    /**
     * The species rig rig/fish/&lt;model name&gt; (FishRigs, FishDef.model). Falls back to the old
     * rule (species ID name) when missing.
     */
    private Optional<RigId> rigFor(FishId fish) {
        Optional<FishDef> def = api.content().snapshot().get(FishDef.TYPE, fish.id());
        if (def.isPresent()) {
            RigId own = FishRigs.rigOf(def.get());
            if (api.rigs().ids().contains(own)) {
                return Optional.of(own);
            }
        }
        RigId byId = new RigId(new HarborId(fish.id().namespace(), "rig/fish/" + fish.id().name()));
        return api.rigs().ids().contains(byId) ? Optional.of(byId) : Optional.empty();
    }

    private @Nullable BlockDisplay floor(World world, TankBounds b, DisplayCaseRules.Theme theme) {
        BlockData data;
        try {
            data = Bukkit.createBlockData(theme.floor());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return world.spawn(
                new Location(world, b.minX(), b.minY(), b.minZ()),
                BlockDisplay.class,
                e -> {
                    e.setPersistent(false);
                    e.setBlock(data);
                    e.setTransformation(
                            new Transformation(
                                    new Vector3f(0.05f, 0.02f, 0.05f),
                                    new AxisAngle4f(),
                                    new Vector3f(b.sizeX() - 0.1f, 0.06f, b.sizeZ() - 0.1f),
                                    new AxisAngle4f()));
                });
    }

    /**
     * The decor's tank model (World housing_decor.yml tank_model: frame, water and decoration) over
     * the glass blocks, stretched to the box and turned so its front (model north) faces the side
     * the tank was placed from. Null if the decor has none or the pack lacks it.
     */
    private @Nullable ItemDisplay tankModel(World world, TankBounds b, DisplayAnchor a) {
        String model = housing.tankModel(a).orElse(null);
        ItemStack item = new ItemStack(Material.GLASS);
        if (model == null || !ItemModels.apply(item, model, api.content().snapshot())) {
            return null;
        }
        String facing = a.facing().toUpperCase(Locale.ROOT);
        boolean sideways = facing.equals("EAST") || facing.equals("WEST");
        float width = sideways ? b.sizeZ() : b.sizeX();
        float depth = sideways ? b.sizeX() : b.sizeZ();
        float yaw =
                switch (facing) {
                    case "SOUTH" -> (float) Math.PI;
                    case "EAST" -> (float) (-Math.PI / 2);
                    case "WEST" -> (float) (Math.PI / 2);
                    default -> 0f;
                };
        Location at =
                new Location(
                        world,
                        b.minX() + b.sizeX() / 2.0,
                        b.minY() + b.sizeY() / 2.0,
                        b.minZ() + b.sizeZ() / 2.0);
        return world.spawn(
                at,
                ItemDisplay.class,
                e -> {
                    e.setPersistent(false);
                    e.setItemStack(item);
                    e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                    e.setTransformation(
                            new Transformation(
                                    new Vector3f(),
                                    new AxisAngle4f(yaw, 0, 1, 0),
                                    new Vector3f(width, b.sizeY(), depth),
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

    // ------------------------------------------------------------------ viewers and swimming

    /** Swims to a random point in the slot's tank (the whole tank for a larger one). */
    static void swim(RigInstance rig, SlotView sv) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location to =
                sv.center
                        .clone()
                        .add(
                                sv.rx > 0 ? r.nextDouble(-sv.rx, sv.rx) : 0,
                                sv.ry > 0 ? r.nextDouble(-sv.ry, sv.ry) : 0,
                                sv.rz > 0 ? r.nextDouble(-sv.rz, sv.rz) : 0);
        Location from = rig.location();
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        if (dx * dx + dz * dz > 1e-4) {
            rig.setHeading((float) Math.toDegrees(Math.atan2(-dx, dz)), 0);
        }
        rig.moveTo(to, 25);
    }

    static void particles(PlotView view, List<Player> viewers) {
        for (SlotView sv : view.views.values()) {
            Particle p = sv.particle;
            if (p == null) {
                continue;
            }
            for (Player player : viewers) {
                try {
                    player.spawnParticle(p, sv.center, 2, 0.25, 0.25, 0.25, 0.01);
                } catch (IllegalArgumentException e) {
                    sv.particle = null; // skip particles that need extra data
                }
            }
        }
    }

    /** Players inside the plot or within view_radius blocks of its border. */
    List<Player> viewers(PlotView view, World world) {
        double r = manager.rules().viewRadius();
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

    static Set<UUID> ids(List<Player> players) {
        Set<UUID> ids = new HashSet<>();
        players.forEach(p -> ids.add(p.getUniqueId()));
        return Set.copyOf(ids);
    }
}
