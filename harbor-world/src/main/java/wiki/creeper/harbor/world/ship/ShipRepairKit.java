package wiki.creeper.harbor.world.ship;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.core.api.HarborSounds;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ShipState;

/** 수리 키트. 타고 있는 배에서 1초마다 진행하고, 내리거나 키트를 놓으면 취소한다. 메인 스레드 전용. */
final class ShipRepairKit {

    static final HarborId REPAIR_KIT = HarborId.parse("item/boat/repair_kit");

    // 선박 소리 ('15. 사운드' 4.4)
    private static final String REPAIR = "harbor:ship.repair";

    private final WorldKit kit;
    private final ShipFleet fleet;
    private final Map<UUID, Integer> repairing = new HashMap<>();

    ShipRepairKit(WorldKit kit, ShipFleet fleet) {
        this.kit = kit;
        this.fleet = fleet;
    }

    void forget(UUID uuid) {
        repairing.remove(uuid);
    }

    void use(Player player) {
        Optional<Afloat> aboard = fleet.aboardAfloat(player);
        if (aboard.isEmpty()) {
            kit.send(player, "world.ship.kit_not_aboard");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (repairing.containsKey(uuid)) {
            return;
        }
        Afloat a = aboard.get();
        int total = kit.balance().repairKitTicks();
        repairing.put(uuid, 0);
        kit.send(
                player,
                "world.ship.kit_start",
                Placeholder.unparsed("seconds", String.valueOf(total / 20)));
        repairStep(player, a, total);
    }

    private void repairStep(Player player, Afloat a, int total) {
        UUID uuid = player.getUniqueId();
        var _ =
                kit.tasks()
                        .later(
                                20,
                                () -> {
                                    Integer done = repairing.get(uuid);
                                    if (done == null) {
                                        return;
                                    }
                                    if (!player.isOnline()
                                            || fleet.aboardAfloat(player)
                                                    .filter(x -> x == a)
                                                    .isEmpty()) {
                                        repairing.remove(uuid);
                                        kit.send(player, "world.ship.kit_cancel");
                                        return;
                                    }
                                    HarborSounds.playAt(WorldKit.loc(player), REPAIR);
                                    int next = done + 20;
                                    if (next < total) {
                                        repairing.put(uuid, next);
                                        repairStep(player, a, total);
                                        return;
                                    }
                                    repairing.remove(uuid);
                                    ItemStack hand = player.getInventory().getItemInMainHand();
                                    if (kit.itemId(hand)
                                            .filter(i -> i.id().equals(REPAIR_KIT))
                                            .isEmpty()) {
                                        kit.send(player, "world.ship.kit_cancel");
                                        return;
                                    }
                                    hand.setAmount(hand.getAmount() - 1);
                                    // 수리 결과는 바로 DB에 쓰므로 키트를 쓴 인벤토리도 곧 저장한다 (서버가 죽으면 키트 복제)
                                    kit.api().players().saveSoon(uuid);
                                    int max = fleet.info(a.row).maxDurability();
                                    int value =
                                            Math.min(
                                                    max,
                                                    a.row.durability()
                                                            + (int)
                                                                    Math.ceil(
                                                                            max
                                                                                    * kit.balance()
                                                                                            .repairKitRatio()));
                                    a.row = a.row.withDurability(value, ShipState.AFLOAT);
                                    fleet.replace(a.row);
                                    long id = a.row.shipId();
                                    var _ =
                                            kit.api()
                                                    .database()
                                                    .asyncVoid(
                                                            c ->
                                                                    ShipRepository.updateState(
                                                                            c,
                                                                            id,
                                                                            value,
                                                                            ShipState.AFLOAT));
                                    kit.send(
                                            player,
                                            "world.ship.kit_done",
                                            Placeholder.unparsed(
                                                    "durability", String.valueOf(value)),
                                            Placeholder.unparsed("max", String.valueOf(max)));
                                });
    }
}
