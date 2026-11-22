package wiki.creeper.harbor.skript.bridge;

import static wiki.creeper.harbor.skript.bridge.Shared.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.FurnitureService;
import wiki.creeper.harbor.core.api.event.CustomBlockBreakEvent;
import wiki.creeper.harbor.core.api.event.CustomBlockPlaceEvent;
import wiki.creeper.harbor.core.api.event.FurnitureInteractEvent;
import wiki.creeper.harbor.core.api.event.FurniturePlaceEvent;
import wiki.creeper.harbor.core.api.event.FurnitureRemoveEvent;
import wiki.creeper.harbor.core.api.event.FurnitureSitEvent;
import wiki.creeper.harbor.core.api.event.FurnitureStandEvent;
import wiki.creeper.harbor.skript.Ids;
import wiki.creeper.harbor.skript.lambda.Ctx;
import wiki.creeper.harbor.skript.lambda.Ext;
import wiki.creeper.harbor.skript.lambda.Registrar;

/**
 * Furniture and custom block syntax (Core, always registered): furniture and block items, the
 * furniture / block ID of items, placed blocks and furniture entities, placing, finding and
 * removing furniture, and the furniture and custom block events.
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

    /** Placed furniture of an entity or location value. */
    private static Optional<FurnitureService.Placed> placed(Object o) {
        return switch (o) {
            case Entity e -> furniture().placedOf(e);
            case Location l -> furniture().furnitureAt(l);
            case Block b -> furniture().furnitureAt(b.getLocation());
            default -> Optional.empty();
        };
    }

    /** Furniture ID of an item, entity or location value. */
    private static Optional<HarborId> furnitureId(Object o) {
        if (o instanceof ItemStack stack) {
            return furniture().furnitureOf(stack);
        }
        return placed(o).map(FurnitureService.Placed::id);
    }

    private static String key(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    public static void register(Registrar r) {
        items(r);
        placed(r);
        events(r);
        removeEvent(r);
    }

    // ------------------------------------------------------------ Items and IDs

    private static void items(Registrar r) {
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
                        c -> {
                            List<String> out = new ArrayList<>();
                            for (Object o : c.all(0)) {
                                furnitureId(o).ifPresent(id -> out.add(id.toString()));
                            }
                            return out;
                        },
                        "[the] harbor furniture id of %itemstacks/entities/locations%",
                        "%itemstacks/entities/locations%'[s] harbor furniture id");
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
                "is harbor furniture",
                c -> {
                    List<Object> values = c.all(0);
                    Optional<HarborId> want = c.string(1).flatMap(s -> Ids.parse(s, FURNITURE));
                    if (values.isEmpty() || (c.has(1) && want.isEmpty())) {
                        return false;
                    }
                    for (Object o : values) {
                        Optional<HarborId> id = furnitureId(o);
                        if (id.isEmpty() || (want.isPresent() && !want.get().equals(id.get()))) {
                            return false;
                        }
                    }
                    return true;
                },
                "%itemstacks/entities/locations% (is|are) [a] harbor furniture [item[s]]"
                        + " [%-string%]",
                "%itemstacks/entities/locations% (isn't|is not|aren't|are not) [a] harbor"
                        + " furniture [item[s]] [%-string%]");
    }

    // ------------------------------------------------------------ Placed furniture

    /** Furniture for "within %number% of %location%" (pattern 0) or "in %chunk%" (pattern 1). */
    private static List<FurnitureService.Placed> nearby(Ctx c) {
        if (c.pattern() == 1) {
            return c.as(0, Chunk.class).map(ch -> furniture().furnitureIn(ch)).orElse(List.of());
        }
        double radius = c.number(0).map(Number::doubleValue).orElse(0.0);
        return c.as(1, Location.class)
                .map(l -> furniture().furnitureNear(l, radius))
                .orElse(List.of());
    }

    private static float yaw(Ctx c, int index, Location at) {
        return c.number(index).map(Number::floatValue).orElse(at.getYaw());
    }

    private static void placed(Registrar r) {
        Ext.expr(r, OfflinePlayer.class, "harbor furniture owner")
                .register(
                        c -> {
                            List<OfflinePlayer> out = new ArrayList<>();
                            for (Object o : c.all(0)) {
                                placed(o)
                                        .map(FurnitureService.Placed::owner)
                                        .ifPresent(u -> out.add(Shared.offline(u)));
                            }
                            return out;
                        },
                        "[the] harbor furniture owner of %entities/locations%");
        Ext.expr(r, Location.class, "harbor furniture location")
                .register(
                        c -> {
                            List<Location> out = new ArrayList<>();
                            for (Object o : c.all(0)) {
                                placed(o).ifPresent(p -> out.add(p.location()));
                            }
                            return out;
                        },
                        "[the] harbor furniture location of %entities/locations%");
        Ext.expr(r, Entity.class, "harbor furniture entity")
                .register(
                        c -> {
                            List<Entity> out = new ArrayList<>();
                            for (Object o : c.all(0)) {
                                placed(o).ifPresent(p -> out.add(p.hitbox()));
                            }
                            return out;
                        },
                        "[the] harbor furniture (entity|hitbox) (at|of) %entities/locations%");
        // pattern 0 radius, 1 chunk
        Ext.expr(r, Entity.class, "harbor furniture near")
                .list()
                .register(
                        c -> nearby(c).stream().map(p -> (Entity) p.hitbox()).toList(),
                        "[all] [the] harbor furniture within %number% [blocks] (of|around)"
                                + " %location%",
                        "[all] [the] harbor furniture in [chunk] %chunk%");

        // pattern 0 admin (no protection check), 1 as a player (protection check, owner)
        Ext.effect(
                r,
                "harbor place furniture",
                c -> {
                    // pattern 0: id 0, location 1, facing 2, owner 3; pattern 1: player 0 first
                    int base = c.pattern() == 0 ? 0 : 1;
                    Optional<HarborId> id = c.string(base).flatMap(s -> Ids.parse(s, FURNITURE));
                    Optional<Location> at = c.as(base + 1, Location.class);
                    Optional<Player> actor = c.pattern() == 0 ? Optional.empty() : c.player(0);
                    if (id.isEmpty() || at.isEmpty() || (c.pattern() == 1 && actor.isEmpty())) {
                        c.set(4, List.of());
                        return;
                    }
                    float yaw = yaw(c, base + 2, at.get());
                    FurnitureService.PlaceResult res =
                            actor.isPresent()
                                    ? furniture().placeAs(actor.get(), id.get(), at.get(), yaw)
                                    : furniture()
                                            .place(
                                                    id.get(),
                                                    at.get(),
                                                    yaw,
                                                    c.offlinePlayer(3)
                                                            .map(OfflinePlayer::getUniqueId)
                                                            .orElse(null));
                    c.set(4, List.of(key(res)));
                },
                "harbor admin place [harbor] furniture %string% at %location% [facing %-number%]"
                        + " [owned by %-offlineplayer%] [and (store|save) [the] result in"
                        + " %-objects%]",
                "make %player% place harbor furniture %string% at %location% [facing %-number%]"
                        + " [and (store|save) [the] result in %-objects%]");
        Ext.effect(
                r,
                "harbor remove furniture",
                c -> {
                    Object o = c.single(0);
                    Optional<FurnitureService.Placed> p = o == null ? Optional.empty() : placed(o);
                    if (p.isEmpty()) {
                        c.set(1, List.of());
                        return;
                    }
                    c.set(
                            1,
                            furniture()
                                    .remove(p.get().hitbox(), c.mark() == 1)
                                    .map(List::of)
                                    .orElse(List.of()));
                },
                "harbor admin remove [the] [harbor] furniture (at|of) %location/entity%"
                        + " [1:and drop [the] item] [and (store|save) [the] item in %-objects%]");
    }

    // ------------------------------------------------------------ Events

    private static void events(Registrar r) {
        r.event(
                "Harbor Furniture Place",
                FurniturePlaceEvent.class,
                "가구를 놓기 직전 (취소 가능. event-player = 놓는 플레이어(관리자 문법이면 없음), event-location,"
                        + " event-string = 가구 ID, event-number = 방향)",
                "harbor furniture place");
        r.value(FurniturePlaceEvent.class, Player.class, FurniturePlaceEvent::player);
        r.value(FurniturePlaceEvent.class, Location.class, FurniturePlaceEvent::location);
        r.value(FurniturePlaceEvent.class, String.class, e -> e.furniture().toString());
        r.value(FurniturePlaceEvent.class, Number.class, FurniturePlaceEvent::yaw);

        r.event(
                "Harbor Furniture Interact",
                FurnitureInteractEvent.class,
                "놓인 가구를 우클릭했을 때, 앉기·회수 전 (취소 가능. event-player, event-location, event-string ="
                        + " 가구 ID, event-entity = 가구 히트박스)",
                "harbor furniture (interact|click)");
        r.value(FurnitureInteractEvent.class, Player.class, FurnitureInteractEvent::player);
        r.value(FurnitureInteractEvent.class, Location.class, FurnitureInteractEvent::location);
        r.value(FurnitureInteractEvent.class, String.class, e -> e.furniture().toString());
        r.value(FurnitureInteractEvent.class, Entity.class, FurnitureInteractEvent::entity);
        Ext.conditionIn(
                r,
                "harbor furniture click is pickup",
                FurnitureInteractEvent.class,
                c -> c.event() instanceof FurnitureInteractEvent e && e.pickup(),
                "[the] harbor furniture click is [a] pick[ ]up",
                "[the] harbor furniture click (isn't|is not) [a] pick[ ]up");

        r.event(
                "Harbor Furniture Sit",
                FurnitureSitEvent.class,
                "가구에 앉기 직전 (취소 가능. event-player, event-location, event-string = 가구 ID)",
                "harbor furniture sit");
        r.value(FurnitureSitEvent.class, Player.class, FurnitureSitEvent::player);
        r.value(FurnitureSitEvent.class, Location.class, FurnitureSitEvent::location);
        r.value(FurnitureSitEvent.class, String.class, e -> e.furniture().toString());

        r.event(
                "Harbor Furniture Stand",
                FurnitureStandEvent.class,
                "가구 자리에서 일어난 뒤 (event-player, event-location = 자리, event-string = 가구 ID)",
                "harbor furniture stand [up]");
        r.value(FurnitureStandEvent.class, Player.class, FurnitureStandEvent::player);
        r.value(FurnitureStandEvent.class, Location.class, FurnitureStandEvent::location);
        r.value(
                FurnitureStandEvent.class,
                String.class,
                e -> {
                    HarborId id = e.furniture();
                    return id == null ? null : id.toString();
                });

        r.event(
                "Harbor Custom Block Place",
                CustomBlockPlaceEvent.class,
                "커스텀 블록을 놓을 때 (취소 가능. event-player, event-block, event-string = 블록 ID)",
                "harbor custom block place");
        r.value(CustomBlockPlaceEvent.class, Player.class, CustomBlockPlaceEvent::player);
        r.value(CustomBlockPlaceEvent.class, Block.class, CustomBlockPlaceEvent::block);
        r.value(CustomBlockPlaceEvent.class, String.class, e -> e.customBlock().toString());

        r.event(
                "Harbor Custom Block Break",
                CustomBlockBreakEvent.class,
                "커스텀 블록을 부술 때 (취소 가능. event-player, event-block, event-string = 블록 ID)",
                "harbor custom block break");
        r.value(CustomBlockBreakEvent.class, Player.class, CustomBlockBreakEvent::player);
        r.value(CustomBlockBreakEvent.class, Block.class, CustomBlockBreakEvent::block);
        r.value(CustomBlockBreakEvent.class, String.class, e -> e.customBlock().toString());
    }

    // ------------------------------------------------------------ Pick-up event

    private static void removeEvent(Registrar r) {
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
