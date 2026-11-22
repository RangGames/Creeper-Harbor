package wiki.creeper.harbor.skript.bridge;

import static wiki.creeper.harbor.skript.bridge.Shared.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.FurnitureService;
import wiki.creeper.harbor.core.api.event.FurnitureRemoveEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;

/**
 * Furniture and custom block syntax (Core, always registered): furniture and block items, the
 * furniture / block ID of items and placed blocks, and the furniture pick-up event.
 *
 * <p>Core's FurnitureService has no API to place or remove furniture at a location, to find
 * furniture at a location or entity, or events for furniture place / interact / sit or custom block
 * place / break; the documentation shows how to cover custom blocks with vanilla events.
 */
public final class FurnitureSyntax {

    private FurnitureSyntax() {}

    private static final String FURNITURE = "furniture/";
    private static final String BLOCK = "block/";

    private static FurnitureService furniture() {
        return api().furniture();
    }

    /** Block of a block or location argument value (empty for anything else). */
    private static Optional<Block> block(Object o) {
        return switch (o) {
            case Block b -> Optional.of(b);
            case Location l -> l.getWorld() == null ? Optional.empty() : Optional.of(l.getBlock());
            default -> Optional.empty();
        };
    }

    /** Custom block ID of a block, location or item value. */
    private static Optional<HarborId> blockId(Object o) {
        if (o instanceof ItemStack stack) {
            return furniture().blockOf(stack);
        }
        return block(o).flatMap(b -> furniture().blockAt(b));
    }

    public static void register(Registrar r) {
        Ext.expr(r, ItemStack.class, "harbor furniture item")
                .single()
                .register(
                        c ->
                                c.string(0)
                                        .flatMap(s -> Ids.parse(s, FURNITURE))
                                        .flatMap(id -> furniture().furnitureItem(id))
                                        .map(List::of)
                                        .orElse(List.of()),
                        "harbor furniture item %string%");
        Ext.expr(r, ItemStack.class, "harbor custom block item")
                .single()
                .register(
                        c ->
                                c.string(0)
                                        .flatMap(s -> Ids.parse(s, BLOCK))
                                        .flatMap(id -> furniture().blockItem(id, 1))
                                        .map(List::of)
                                        .orElse(List.of()),
                        "harbor custom block item %string%");

        Ext.expr(r, String.class, "harbor furniture id")
                .register(
                        c ->
                                c.all(0).stream()
                                        .filter(ItemStack.class::isInstance)
                                        .flatMap(
                                                o ->
                                                        furniture()
                                                                .furnitureOf((ItemStack) o)
                                                                .stream())
                                        .map(HarborId::toString)
                                        .toList(),
                        "[the] harbor furniture id of %itemstacks%",
                        "%itemstacks%'[s] harbor furniture id");
        Ext.expr(r, String.class, "harbor custom block id")
                .register(
                        c -> {
                            List<String> out = new ArrayList<>();
                            for (Object o : c.all(0)) {
                                blockId(o).ifPresent(id -> out.add(id.toString()));
                            }
                            return out;
                        },
                        "[the] harbor custom block id of %blocks/locations/itemstacks%",
                        "%blocks/locations/itemstacks%'[s] harbor custom block id");

        Ext.condition(
                r,
                "is harbor custom block",
                c -> {
                    List<Object> values = c.all(0);
                    Optional<HarborId> want = c.string(1).flatMap(s -> Ids.parse(s, BLOCK));
                    if (values.isEmpty() || (c.has(1) && want.isEmpty())) {
                        return false;
                    }
                    for (Object o : values) {
                        Optional<HarborId> id = blockId(o);
                        if (id.isEmpty() || (want.isPresent() && !want.get().equals(id.get()))) {
                            return false;
                        }
                    }
                    return true;
                },
                "%blocks/locations/itemstacks% (is|are) [a] harbor custom block[s] [%-string%]",
                "%blocks/locations/itemstacks% (isn't|is not|aren't|are not) [a] harbor custom"
                        + " block[s] [%-string%]");
        Ext.condition(
                r,
                "is harbor furniture item",
                c -> {
                    List<Object> values = c.all(0);
                    Optional<HarborId> want = c.string(1).flatMap(s -> Ids.parse(s, FURNITURE));
                    if (values.isEmpty() || (c.has(1) && want.isEmpty())) {
                        return false;
                    }
                    for (Object o : values) {
                        Optional<HarborId> id =
                                o instanceof ItemStack s
                                        ? furniture().furnitureOf(s)
                                        : Optional.empty();
                        if (id.isEmpty() || (want.isPresent() && !want.get().equals(id.get()))) {
                            return false;
                        }
                    }
                    return true;
                },
                "%itemstacks% (is|are) [a] harbor furniture [item[s]] [%-string%]",
                "%itemstacks% (isn't|is not|aren't|are not) [a] harbor furniture [item[s]]"
                        + " [%-string%]");

        // ------------------------------------------------------------ Pick-up event
        r.event(
                "Harbor Furniture Remove",
                FurnitureRemoveEvent.class,
                "플레이어가 가구를 회수하기 직전 (취소 가능. event-player, event-location, event-string = 가구"
                        + " ID, event-item = 놓을 때 쓴 아이템)",
                "harbor furniture (remove|pick[ ]up)");
        r.value(FurnitureRemoveEvent.class, Player.class, FurnitureRemoveEvent::player);
        r.value(FurnitureRemoveEvent.class, Location.class, FurnitureRemoveEvent::location);
        r.value(FurnitureRemoveEvent.class, String.class, e -> e.furniture().toString());
        r.value(FurnitureRemoveEvent.class, ItemStack.class, FurnitureRemoveEvent::item);
        Ext.conditionIn(
                r,
                "harbor furniture removal allowed",
                FurnitureRemoveEvent.class,
                c -> c.event() instanceof FurnitureRemoveEvent e && e.allowed(),
                "harbor furniture removal is allowed",
                "harbor furniture removal (isn't|is not) allowed");
        Ext.effectIn(
                r,
                "harbor allow furniture removal",
                FurnitureRemoveEvent.class,
                c -> {
                    if (c.event() instanceof FurnitureRemoveEvent e) {
                        e.setAllowed(c.mark() == 0);
                    }
                },
                "harbor (0:allow|1:deny) [the] furniture removal");
        Ext.effectIn(
                r,
                "harbor return furniture item",
                FurnitureRemoveEvent.class,
                c -> {
                    if (c.event() instanceof FurnitureRemoveEvent e) {
                        e.setReturnItem(c.mark() == 0);
                    }
                },
                "harbor (0:return|1:(don't|do not) return) [the] removed furniture item");
    }
}
