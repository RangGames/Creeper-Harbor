package wiki.creeper.harbor.core.furniture;

import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.content.type.CustomBlockDef;
import wiki.creeper.harbor.common.content.type.CustomBlockDef.Breaking;
import wiki.creeper.harbor.common.content.type.CustomBlockDef.Tool;

/**
 * Custom block mining speed (proposed). The client treats the block as a note block (hardness 0.8,
 * axe) and predicts mining time, so when mining starts a multiplier is applied to the player's
 * block_break_speed attribute so cracks follow the configured hardness and tool. The attribute is
 * shared by client and server, so the animation and the actual break time match. The multiplier is
 * removed when the player stops, breaks the block, or switches the held slot (also cleared on quit
 * and join).
 *
 * <p>Multiplier = (configured tool speed / tool speed against a note block) × (0.8 / hardness) ×
 * (30 / (30 if harvestable, else 100)). Tool speed is measured against vanilla blocks that use the
 * same tool (pickaxe = stone and ores by tier, axe = planks, shovel = dirt, hoe = hay, sword =
 * cobweb). Other multipliers such as Haste or underwater apply equally to both sides, so they are
 * left alone.
 */
public final class BlockBreakSpeed implements Listener {

    @SuppressWarnings("deprecation") // Uses a fixed namespace without a plugin instance
    private static final NamespacedKey MODIFIER = new NamespacedKey("harbor", "custom_block_break");

    private static final double NOTE_BLOCK_HARDNESS = 0.8;

    /** Upper bound of block_break_speed (vanilla attribute range). */
    private static final double MAX_FACTOR = 1024;

    private final CustomBlockManager blocks;

    public BlockBreakSpeed(CustomBlockManager blocks) {
        this.blocks = blocks;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        clear(player);
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        Optional<CustomBlockDef> def = blocks.at(event.getBlock());
        if (def.isEmpty() || def.get().breaking().vanilla()) {
            return;
        }
        double factor = factor(def.get().breaking(), event.getItemInHand());
        if (Math.abs(factor - 1) < 1e-4) {
            return;
        }
        AttributeInstance speed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (speed != null) {
            speed.addTransientModifier(
                    new AttributeModifier(
                            MODIFIER,
                            factor - 1,
                            AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                            EquipmentSlotGroup.ANY));
        }
    }

    @EventHandler
    public void onAbort(BlockDamageAbortEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        clear(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    /** Removes the multiplier from online players on disable. */
    public void shutdown() {
        Bukkit.getOnlinePlayers().forEach(BlockBreakSpeed::clear);
    }

    private static void clear(Player player) {
        AttributeInstance speed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (speed != null) {
            speed.removeModifier(MODIFIER);
        }
    }

    /** Mining speed multiplier relative to a note block (0 = unbreakable). */
    static double factor(Breaking breaking, ItemStack tool) {
        if (breaking.unbreakable()) {
            return 0;
        }
        if (breaking.hardness() <= 0) {
            return MAX_FACTOR;
        }
        double wanted = reference(breaking).getDestroySpeed(tool, true);
        double noteBlock = Material.NOTE_BLOCK.createBlockData().getDestroySpeed(tool, true);
        double divisor = canHarvest(breaking, tool) ? 30 : 100;
        double factor =
                (wanted / Math.max(noteBlock, 1e-3))
                        * (NOTE_BLOCK_HARDNESS / breaking.hardness())
                        * (30 / divisor);
        return Math.clamp(factor, 0, MAX_FACTOR);
    }

    /**
     * Whether the tool is correct (always true unless requires_tool). Pickaxes are checked by tier
     * too.
     */
    static boolean canHarvest(Breaking breaking, ItemStack tool) {
        if (!breaking.requiresTool() || breaking.tool() == Tool.NONE) {
            return true;
        }
        BlockData reference = reference(breaking);
        if (reference.getDestroySpeed(tool, false) <= 1) {
            return false;
        }
        return breaking.tool() != Tool.PICKAXE || reference.isPreferredTool(tool);
    }

    /**
     * A vanilla block that tool mines well (for pickaxes, an ore with the same tier requirement).
     */
    private static BlockData reference(Breaking breaking) {
        Material material =
                switch (breaking.tool()) {
                    case NONE -> Material.GLASS;
                    case AXE -> Material.OAK_PLANKS;
                    case SHOVEL -> Material.DIRT;
                    case HOE -> Material.HAY_BLOCK;
                    case SWORD -> Material.COBWEB;
                    case PICKAXE -> {
                        CustomBlockDef.Tier tier = breaking.tier();
                        yield tier == null
                                ? Material.STONE
                                : switch (tier) {
                                    case WOOD -> Material.STONE;
                                    case STONE -> Material.IRON_ORE;
                                    case IRON -> Material.DIAMOND_ORE;
                                    case DIAMOND -> Material.OBSIDIAN;
                                };
                    }
                };
        return material.createBlockData();
    }
}
