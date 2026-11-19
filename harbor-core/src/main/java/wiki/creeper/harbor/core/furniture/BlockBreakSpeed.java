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
 * 커스텀 블록 캐는 속도 (추가 제안). 클라이언트는 노트블록(단단함 0.8, 도끼)으로 알고 캐는 시간을 예측하므로, 캐기 시작할 때 플레이어의
 * block_break_speed 속성에 배율을 걸어 정한 단단함·도구대로 금이 가게 한다. 속성은 클라이언트와 서버가 같이 쓰므로 애니메이션과 실제 부서지는 시점이 맞는다.
 * 그만두거나 부수거나 들고 있는 칸을 바꾸면 배율을 뗀다 (나갈 때·들어올 때도 지운다).
 *
 * <p>배율 = (정한 도구 속도 / 노트블록 기준 도구 속도) × (0.8 / 단단함) × (30 / (캐지면 30, 아니면 100)). 도구 속도는 같은 도구를 쓰는 바닐라
 * 블록(곡괭이 = 돌·등급별 광석, 도끼 = 판자, 삽 = 흙, 괭이 = 건초, 검 = 거미줄)으로 잰다. 성급함·물속 등 다른 배율은 양쪽에 똑같이 곱해지므로 그대로 둔다.
 */
public final class BlockBreakSpeed implements Listener {

    @SuppressWarnings("deprecation") // 플러그인 인스턴스 없이 고정 네임스페이스를 쓴다
    private static final NamespacedKey MODIFIER = new NamespacedKey("harbor", "custom_block_break");

    private static final double NOTE_BLOCK_HARDNESS = 0.8;

    /** block_break_speed 상한 (바닐라 속성 범위). */
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

    /** 꺼질 때 접속자 배율을 뗀다. */
    public void shutdown() {
        Bukkit.getOnlinePlayers().forEach(BlockBreakSpeed::clear);
    }

    private static void clear(Player player) {
        AttributeInstance speed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED);
        if (speed != null) {
            speed.removeModifier(MODIFIER);
        }
    }

    /** 노트블록 기준 대비 캐는 속도 배율 (0 = 부술 수 없음). */
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

    /** 맞는 도구인지 (requires_tool이 아니면 언제나 true). 곡괭이는 등급까지 본다. */
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

    /** 그 도구로 잘 캐지는 바닐라 블록 (곡괭이는 등급 조건이 같은 광석). */
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
