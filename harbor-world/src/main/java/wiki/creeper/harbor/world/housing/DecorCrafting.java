package wiki.creeper.harbor.world.housing;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.content.DecorDef;

/**
 * Crafting with decor (proposed). Decor items stay ordinary blocks for vanilla recipes; a result
 * made only from decor (or from earlier crafted decor) is marked as crafted decor: a "부두 장식" lore
 * line and its share of the decor that went in, per item (decor ID → amount, e.g. 6 planks into 4
 * stairs = 1.5 planks per stair). Recipes mixing decor with plain items give nothing, so plain
 * items are never locked into housing.
 *
 * <p>Crafted decor behaves like decor: placed in a plot it is tagged in the chunk PDC, breaking it
 * puts the crafted item into the plot owner's inventory (others return its share to the owner's
 * storage), and leaving the housing world turns it back into its share in storage (whole items
 * only; the fractional rest is lost).
 */
final class DecorCrafting implements Listener {

    private static final String BLOCK_PREFIX = "crafted|";
    private static final double EPSILON = 1e-6;

    private final WorldKit kit;
    private final HousingManager housing;
    private final DecorManager decor;
    private final DecorPlacement placement;

    DecorCrafting(
            WorldKit kit, HousingManager housing, DecorManager decor, DecorPlacement placement) {
        this.kit = kit;
        this.housing = housing;
        this.decor = decor;
        this.placement = placement;
    }

    private NamespacedKey key() {
        return kit.key("decor_crafted");
    }

    // ------------------------------------------------------------------ items

    /** Decor share of one crafted decor item (empty if the stack is not crafted decor). */
    Map<HarborId, Double> share(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Map.of();
        }
        String raw = stack.getPersistentDataContainer().get(key(), PersistentDataType.STRING);
        return raw == null ? Map.of() : parse(raw);
    }

    private ItemStack mark(ItemStack stack, Map<HarborId, Double> share) {
        stack.editPersistentDataContainer(
                pdc -> pdc.set(key(), PersistentDataType.STRING, format(share)));
        List<Component> lines = new ArrayList<>();
        ItemLore lore = stack.getData(DataComponentTypes.LORE);
        if (lore != null) {
            lines.addAll(lore.lines());
        }
        lines.add(
                kit.api()
                        .messages()
                        .get("world.decor.lore.crafted")
                        .decoration(TextDecoration.ITALIC, false));
        stack.setData(DataComponentTypes.LORE, ItemLore.lore(lines));
        return stack;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent event) {
        CraftingInventory inv = event.getInventory();
        ItemStack result = inv.getResult();
        if (result == null || result.isEmpty()) {
            return;
        }
        Map<HarborId, Double> total = new HashMap<>();
        boolean any = false;
        boolean plain = false;
        for (ItemStack in : inv.getMatrix()) {
            if (in == null || in.isEmpty()) {
                continue;
            }
            Optional<DecorDef> d = decor.def(in);
            if (d.isPresent()) {
                total.merge(d.get().id(), 1.0, Double::sum);
                any = true;
                continue;
            }
            Map<HarborId, Double> s = share(in);
            if (!s.isEmpty()) {
                s.forEach((id, v) -> total.merge(id, v, Double::sum));
                any = true;
                continue;
            }
            plain = true;
        }
        if (!any) {
            return;
        }
        if (plain) {
            inv.setResult(null);
            return;
        }
        int amount = Math.max(1, result.getAmount());
        Map<HarborId, Double> per = new LinkedHashMap<>();
        total.forEach((id, v) -> per.put(id, v / amount));
        inv.setResult(mark(result.clone(), per));
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Map<HarborId, Double> share = share(event.getItemInHand());
        Block block = event.getBlockPlaced();
        if (share.isEmpty() || housing.stateAt(block.getLocation()).isEmpty()) {
            return;
        }
        Material placed = block.getBlockData().getPlacementMaterial();
        block.getChunk()
                .getPersistentDataContainer()
                .set(
                        placement.blockKey(block),
                        PersistentDataType.STRING,
                        BLOCK_PREFIX + placed.getKey() + "|" + format(share));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        NamespacedKey key = placement.blockKey(block);
        String raw =
                block.getChunk().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (raw == null || !raw.startsWith(BLOCK_PREFIX)) {
            return;
        }
        block.getChunk().getPersistentDataContainer().remove(key);
        String rest = raw.substring(BLOCK_PREFIX.length());
        int bar = rest.indexOf('|');
        Material material = bar < 0 ? null : Material.matchMaterial(rest.substring(0, bar));
        if (material == null || block.getBlockData().getPlacementMaterial() != material) {
            return; // Moved or replaced since: break it normally
        }
        Map<HarborId, Double> share = parse(rest.substring(bar + 1));
        if (share.isEmpty()) {
            return;
        }
        event.setDropItems(false);
        Player breaker = event.getPlayer();
        UUID owner =
                housing.stateAt(block.getLocation())
                        .map(p -> p.info.owner())
                        .orElse(breaker.getUniqueId());
        if (owner.equals(breaker.getUniqueId())) {
            decor.give(breaker, mark(ItemStack.of(material), share), block.getWorld());
            return;
        }
        share.forEach(
                (id, v) -> {
                    int n = whole(v);
                    if (n > 0) {
                        decor.creditAfterWorldSave(block.getWorld(), owner, id, n);
                    }
                });
    }

    // ------------------------------------------------------------------ format

    /** Whole items in a share total (rounding noise from divisions is ignored). */
    static int whole(double amount) {
        return (int) Math.floor(amount + EPSILON);
    }

    static String format(Map<HarborId, Double> share) {
        return share.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(";"));
    }

    static Map<HarborId, Double> parse(String raw) {
        Map<HarborId, Double> out = new LinkedHashMap<>();
        for (String part : raw.split(";", -1)) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            Optional<HarborId> id = HarborId.tryParse(part.substring(0, eq));
            try {
                double v = Double.parseDouble(part.substring(eq + 1));
                if (id.isPresent() && v > 0) {
                    out.put(id.get(), v);
                }
            } catch (NumberFormatException e) {
                // Skip a broken entry
            }
        }
        return out;
    }
}
