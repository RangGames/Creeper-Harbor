package wiki.creeper.harbor.world.ship;

import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.content.ShipDef;

/** 화물 칸. 상자 GUI로 열고(상자 보트를 열거나 /선박 화물), 닫을 때 지연 쓰기로 저장한다. 메인 스레드 전용. */
final class ShipCargo {

    /** 화물 GUI. */
    private static final class Cargo implements InventoryHolder {
        final long shipId;
        final UUID owner;
        @Nullable Inventory inventory;

        Cargo(long shipId, UUID owner) {
            this.shipId = shipId;
            this.owner = owner;
        }

        @Override
        public Inventory getInventory() {
            Inventory inv = inventory;
            if (inv == null) {
                throw new IllegalStateException("화물 GUI가 아직 없습니다");
            }
            return inv;
        }
    }

    private final WorldKit kit;
    private final ShipFleet fleet;

    ShipCargo(WorldKit kit, ShipFleet fleet) {
        this.kit = kit;
        this.fleet = fleet;
    }

    /** 상자 보트를 열면 화물 GUI. */
    void onOpen(InventoryOpenEvent event) {
        if (event.getInventory().getHolder(false) instanceof ChestBoat boat
                && fleet.isShipBoat(boat.getUniqueId())
                && event.getPlayer() instanceof Player player) {
            event.setCancelled(true);
            Long id = fleet.shipIdOf(boat.getUniqueId());
            Afloat a = id == null ? null : fleet.afloat(id);
            if (a != null) {
                var _ = kit.tasks().sync(() -> open(player, a));
            }
        }
    }

    /** /선박 화물: 타고 있거나 8블록 안에 있는 자기 배. */
    void open(Player player) {
        Optional<Afloat> a = fleet.aboardAfloat(player);
        if (a.isEmpty()) {
            a =
                    fleet.afloatList().stream()
                            .filter(x -> x.row.owner().equals(player.getUniqueId()))
                            .filter(
                                    x ->
                                            x.boat.getWorld().equals(player.getWorld())
                                                    && WorldKit.loc(x.boat)
                                                                    .distanceSquared(
                                                                            WorldKit.loc(player))
                                                            < 64)
                            .findFirst();
        }
        if (a.isEmpty()) {
            kit.send(player, "world.ship.cargo_far");
            return;
        }
        open(player, a.get());
    }

    private void open(Player player, Afloat a) {
        if (!a.row.owner().equals(player.getUniqueId())) {
            kit.send(player, "world.ship.not_owner");
            return;
        }
        Optional<ShipDef> def = fleet.def(a.row.type());
        int slots = def.map(d -> ShipMath.cargo(d, a.row.upgrades())).orElse(0);
        if (slots <= 0) {
            kit.send(player, "world.ship.no_cargo");
            return;
        }
        Cargo holder = new Cargo(a.row.shipId(), a.row.owner());
        Inventory inv =
                Bukkit.createInventory(
                        holder,
                        slots,
                        kit.api()
                                .messages()
                                .get(
                                        "world.ship.cargo_title",
                                        Placeholder.unparsed(
                                                "name", def.map(ShipDef::name).orElse(""))));
        holder.inventory = inv;
        byte[] raw = a.row.cargo();
        if (raw != null && raw.length > 0) {
            try {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(raw);
                for (int i = 0; i < Math.min(items.length, slots); i++) {
                    inv.setItem(i, items[i]);
                }
            } catch (RuntimeException e) {
                kit.log().warn("화물을 읽지 못했습니다 {}: {}", a.row.shipId(), String.valueOf(e));
            }
        }
        player.openInventory(inv);
    }

    void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Cargo cargo)) {
            return;
        }
        byte[] bytes = ItemStack.serializeItemsAsBytes(event.getInventory().getContents());
        Afloat a = fleet.afloat(cargo.shipId);
        if (a != null) {
            a.row = a.row.withCargo(bytes);
            fleet.replace(a.row);
        } else {
            fleet.rows(cargo.owner).stream()
                    .filter(r -> r.shipId() == cargo.shipId)
                    .findFirst()
                    .ifPresent(r -> fleet.replace(r.withCargo(bytes)));
        }
        long id = cargo.shipId;
        kit.api()
                .writeBehind()
                .enqueue(
                        "player:" + cargo.owner + ":world:cargo:" + id,
                        c -> ShipRepository.updateCargo(c, id, bytes));
    }
}
