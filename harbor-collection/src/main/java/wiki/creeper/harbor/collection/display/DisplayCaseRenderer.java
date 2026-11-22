package wiki.creeper.harbor.collection.display;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
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
import wiki.creeper.harbor.core.api.RigInstance;
import wiki.creeper.harbor.core.api.RigOptions;
import wiki.creeper.harbor.world.api.DisplayAnchor;
import wiki.creeper.harbor.world.api.HousingService;
import wiki.creeper.harbor.world.api.PlotInfo;

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
            double length =
                    api.rigs()
                            .definition(rig.get())
                            .map(r -> r.length() * r.scale())
                            .filter(l -> l > 0)
                            .orElse(0.5);
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

    // ------------------------------------------------------------------ viewers and swimming

    static void swim(RigInstance rig, Location center, double radius) {
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
