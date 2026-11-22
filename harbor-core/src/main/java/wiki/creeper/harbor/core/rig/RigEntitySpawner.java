package wiki.creeper.harbor.core.rig;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.rig.BakedRig;
import wiki.creeper.harbor.common.rig.RigDef;
import wiki.creeper.harbor.common.rig.RigPlayback;
import wiki.creeper.harbor.core.api.ItemModels;
import wiki.creeper.harbor.core.api.RigOptions;

/**
 * Spawns the entities of one rig instance (split out of {@link RigManager}): the root ItemDisplay,
 * part Display passengers and hitbox Interactions. Main thread only.
 */
final class RigEntitySpawner {

    /** The spawned entities of one instance. */
    static final class Spawned {
        private final ItemDisplay root;
        private final Display[] parts;
        private final Interaction[] hitboxes;

        Spawned(ItemDisplay root, Display[] parts, Interaction[] hitboxes) {
            this.root = root;
            this.parts = parts;
            this.hitboxes = hitboxes;
        }

        ItemDisplay root() {
            return root;
        }

        Display[] parts() {
            return parts;
        }

        Interaction[] hitboxes() {
            return hitboxes;
        }
    }

    private final Supplier<ContentSnapshot> content;

    RigEntitySpawner(Supplier<ContentSnapshot> content) {
        this.content = content;
    }

    /**
     * Spawns root, parts and hitboxes. If any spawn fails, everything spawned so far is removed.
     */
    Spawned spawn(
            World world,
            Location base,
            BakedRig rig,
            RigPlayback playback,
            RigOptions options,
            boolean visible,
            UUID instanceId,
            int moveTicks,
            long now) {
        RigDef def = rig.def();
        ItemDisplay root =
                world.spawn(
                        base,
                        ItemDisplay.class,
                        e -> {
                            prepare(e, visible, instanceId);
                            e.setItemStack(ItemStack.empty());
                            e.setTeleportDuration(moveTicks);
                        });
        List<Entity> spawned = new ArrayList<>();
        spawned.add(root);
        Display[] parts = new Display[rig.parts().size()];
        Interaction[] hitboxes =
                options.onHit() == null
                        ? new Interaction[0]
                        : new Interaction[rig.hitboxes().size()];
        try {
            for (int p = 0; p < parts.length; p++) {
                parts[p] =
                        spawnPart(
                                world,
                                base,
                                rig,
                                p,
                                playback,
                                options,
                                visible,
                                instanceId,
                                moveTicks,
                                now);
                spawned.add(parts[p]);
                var _ = root.addPassenger(parts[p]);
            }
            for (int h = 0; h < hitboxes.length; h++) {
                RigDef.Hitbox box = rig.hitboxes().get(h).hitbox();
                double k = options.scale() * def.scale();
                hitboxes[h] =
                        world.spawn(
                                base,
                                Interaction.class,
                                e -> {
                                    prepare(e, visible, instanceId);
                                    e.setInteractionWidth((float) (box.width() * k));
                                    e.setInteractionHeight((float) (box.height() * k));
                                    e.setResponsive(true);
                                });
                spawned.add(hitboxes[h]);
            }
        } catch (RuntimeException e) {
            spawned.forEach(Entity::remove);
            throw e;
        }
        return new Spawned(root, parts, hitboxes);
    }

    private Display spawnPart(
            World world,
            Location base,
            BakedRig rig,
            int index,
            RigPlayback playback,
            RigOptions options,
            boolean visible,
            UUID instanceId,
            int moveTicks,
            long now) {
        BakedRig.PartRef ref = rig.parts().get(index);
        RigDef.Part part = ref.part();
        Consumer<Display> common =
                d -> {
                    prepare(d, visible, instanceId);
                    d.setTeleportDuration(moveTicks);
                    d.setInterpolationDuration(0);
                    d.setViewRange((float) rig.def().viewRange());
                    d.setTransformation(
                            CoreRig.toBukkit(
                                    playback.pose(index, now), options.scale(), part.flip()));
                    if (options.blockLight() >= 0) {
                        d.setBrightness(
                                new Display.Brightness(options.blockLight(), options.skyLight()));
                    } else if (part.brightness() instanceof RigDef.Brightness(int block, int sky)) {
                        d.setBrightness(new Display.Brightness(block, sky));
                    }
                };
        return switch (part.kind()) {
            case ITEM ->
                    world.spawn(
                            base,
                            ItemDisplay.class,
                            d -> {
                                common.accept(d);
                                // Without the custom model (resource pack not ready yet), leave
                                // an empty display instead of paper
                                ItemStack stack = ItemStack.of(Material.PAPER);
                                if (ItemModels.apply(stack, part.model(), content.get())) {
                                    d.setItemStack(stack);
                                }
                                d.setItemDisplayTransform(
                                        ItemDisplay.ItemDisplayTransform.valueOf(
                                                part.transform().name()));
                            });
            case BLOCK ->
                    world.spawn(
                            base,
                            BlockDisplay.class,
                            d -> {
                                common.accept(d);
                                d.setBlock(Bukkit.createBlockData(part.model()));
                            });
        };
    }

    private static void prepare(Entity e, boolean visible, UUID instanceId) {
        e.setPersistent(false);
        e.setVisibleByDefault(visible);
        e.setSilent(true);
        e.getPersistentDataContainer()
                .set(RigManager.RIG_KEY, PersistentDataType.STRING, instanceId.toString());
    }
}
