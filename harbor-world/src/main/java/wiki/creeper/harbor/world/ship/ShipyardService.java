package wiki.creeper.harbor.world.ship;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.type.ItemDef;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.world.WorldKit;
import wiki.creeper.harbor.world.api.ShipState;
import wiki.creeper.harbor.world.api.UpgradePath;
import wiki.creeper.harbor.world.bridge.Payments;
import wiki.creeper.harbor.world.content.ShipDef;

/**
 * Shipyard trades (purchase, upgrade, repair), deed issuing, and the admin commands' ship giving
 * and full repair. Payments go through Payments (ledger) with the ships row change in the same
 * transaction. Main thread only.
 */
final class ShipyardService {

    private final WorldKit kit;
    private final ShipFleet fleet;
    private final Payments payments;

    ShipyardService(WorldKit kit, ShipFleet fleet, Payments payments) {
        this.kit = kit;
        this.fleet = fleet;
        this.payments = payments;
    }

    void giveDeed(Player player, ShipDef def, long shipId) {
        Optional<ItemDef> item = kit.api().content().snapshot().get(ItemDef.TYPE, def.item().id());
        if (item.isEmpty()) {
            return;
        }
        ItemStack deed = kit.api().items().create(item.get(), 1);
        deed.editPersistentDataContainer(
                pdc -> pdc.set(kit.key("ship_id"), PersistentDataType.LONG, shipId));
        kit.give(player, deed);
    }

    /**
     * Purchase conditions (level, job tier, flags; D3 ocean explorer = Lv50 after the 2nd job
     * advancement). Returns a message key if unmet.
     */
    @Nullable String requirement(Player player, ShipDef def) {
        HarborPlayer hp = kit.api().players().get(player);
        ShipDef.Requires r = def.requires();
        if (hp.level() < r.level() || hp.jobTier().level() < r.jobTier()) {
            return "world.yard.req_level";
        }
        for (String flag : r.flags()) {
            if (!kit.api().flags().hasPlayer(player.getUniqueId(), flag)) {
                return "world.yard.req_story";
            }
        }
        return null;
    }

    void buy(Player player, ShipDef def) {
        UUID uuid = player.getUniqueId();
        if (fleet.rows(uuid).stream().anyMatch(r -> r.type().equals(def.id()))) {
            kit.send(player, "world.yard.owned");
            return;
        }
        String req = requirement(player, def);
        if (req != null) {
            kit.send(
                    player,
                    req,
                    Placeholder.unparsed("level", String.valueOf(def.requires().level())),
                    Placeholder.unparsed("tier", String.valueOf(def.requires().jobTier())));
            return;
        }
        int season = kit.season();
        long[] newId = {-1};
        var _ =
                payments.charge(
                                player,
                                def.price(),
                                LedgerReason.SHIP_PURCHASE,
                                "ship:s" + season + ":" + def.id().name(),
                                c ->
                                        newId[0] =
                                                ShipRepository.insert(
                                                        c,
                                                        uuid,
                                                        season,
                                                        def.id(),
                                                        def.durability()))
                        .thenAccept(
                                result -> {
                                    if (!result.isOk()) {
                                        kit.send(player, Payments.failKey(result));
                                        return;
                                    }
                                    fleet.addRow(
                                            uuid,
                                            new ShipRepository.Row(
                                                    newId[0],
                                                    uuid,
                                                    def.id(),
                                                    Map.of(),
                                                    def.durability(),
                                                    ShipState.STORED,
                                                    null));
                                    if (player.isOnline()) {
                                        giveDeed(player, def, newId[0]);
                                    }
                                    kit.send(
                                            player,
                                            "world.yard.bought",
                                            Placeholder.unparsed("name", def.name()),
                                            Placeholder.unparsed(
                                                    "price", String.valueOf(def.price())));
                                });
    }

    void upgrade(Player player, ShipRepository.Row row, UpgradePath path) {
        Optional<ShipDef> def = fleet.def(row.type());
        if (def.isEmpty() || def.get().price() <= 0) {
            kit.send(player, "world.yard.no_upgrade");
            return;
        }
        if (fleet.isAfloat(row.shipId())) {
            kit.send(player, "world.yard.recall_first");
            return;
        }
        int level = row.upgrades().getOrDefault(path, 0);
        if (level >= def.get().maxUpgrade(path)) {
            kit.send(player, "world.yard.max_upgrade");
            return;
        }
        int nextLevel = level + 1;
        long cost = ShipMath.upgradeCost(def.get(), kit.balance().upgradeCost(nextLevel));
        Map<UpgradePath, Integer> after = new EnumMap<>(UpgradePath.class);
        after.putAll(row.upgrades());
        after.put(path, nextLevel);
        int oldMax = ShipMath.maxDurability(def.get(), row.upgrades());
        int newMax = ShipMath.maxDurability(def.get(), after);
        int durability =
                row.state() == ShipState.WRECKED
                        ? row.durability()
                        : Math.min(newMax, row.durability() + (newMax - oldMax));
        Map<UpgradePath, Integer> before = row.upgrades();
        long id = row.shipId();
        var _ =
                payments.charge(
                                player,
                                cost,
                                LedgerReason.SHIP_UPGRADE,
                                "shipup:" + id + ":" + path.key() + ":" + nextLevel,
                                c -> ShipRepository.upgrade(c, id, before, after, durability))
                        .thenAccept(
                                result -> {
                                    if (!result.isOk()) {
                                        kit.send(player, Payments.failKey(result));
                                        return;
                                    }
                                    fleet.replace(row.withUpgrades(after, durability));
                                    kit.send(
                                            player,
                                            "world.yard.upgraded",
                                            Placeholder.unparsed(
                                                    "path",
                                                    kit.api()
                                                                    .messages()
                                                                    .has(
                                                                            "world.yard.path."
                                                                                    + path.key())
                                                            ? plain("world.yard.path." + path.key())
                                                            : path.key()),
                                            Placeholder.unparsed(
                                                    "level", String.valueOf(nextLevel)),
                                            Placeholder.unparsed("price", String.valueOf(cost)));
                                });
    }

    long repairQuote(Player player, ShipRepository.Row row) {
        Optional<ShipDef> def = fleet.def(row.type());
        if (def.isEmpty()) {
            return 0;
        }
        return payments.repairQuote(
                player.getUniqueId(), def.get().price(), row.state() == ShipState.WRECKED);
    }

    void repair(Player player, ShipRepository.Row row) {
        Optional<ShipDef> def = fleet.def(row.type());
        if (def.isEmpty()) {
            return;
        }
        int max = ShipMath.maxDurability(def.get(), row.upgrades());
        if (row.durability() >= max && row.state() != ShipState.WRECKED) {
            kit.send(player, "world.yard.no_repair");
            return;
        }
        if (fleet.isAfloat(row.shipId())) {
            kit.send(player, "world.yard.recall_first");
            return;
        }
        long cost = repairQuote(player, row);
        long id = row.shipId();
        var _ =
                payments.repair(
                                player,
                                cost,
                                "shiprepair:" + id + ":" + System.currentTimeMillis(),
                                c -> ShipRepository.updateState(c, id, max, ShipState.STORED))
                        .thenAccept(
                                result -> {
                                    if (!result.isOk()) {
                                        kit.send(player, Payments.failKey(result));
                                        return;
                                    }
                                    fleet.replace(row.withDurability(max, ShipState.STORED));
                                    kit.send(
                                            player,
                                            "world.yard.repaired",
                                            Placeholder.unparsed("price", String.valueOf(cost)));
                                });
    }

    private String plain(String key) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(kit.api().messages().get(key));
    }

    /** Admin command: give a ship (same row as a shipyard purchase, no payment). */
    CompletableFuture<Boolean> adminGive(Player player, ShipDef def) {
        UUID uuid = player.getUniqueId();
        int season = kit.season();
        return kit.api()
                .database()
                .async(
                        c ->
                                ShipRepository.insertStarter(
                                        c, uuid, season, def.id(), def.durability()))
                .thenCompose(
                        ok -> kit.api().database().async(c -> ShipRepository.load(c, uuid, season)))
                .thenApplyAsync(
                        rows -> {
                            fleet.setRows(uuid, rows);
                            rows.stream()
                                    .filter(r -> r.type().equals(def.id()))
                                    .findFirst()
                                    .ifPresent(r -> giveDeed(player, def, r.shipId()));
                            return true;
                        },
                        kit.tasks().main());
    }

    /** Admin command: fully repair all ships. */
    void adminRepair(Player player) {
        for (ShipRepository.Row row : new ArrayList<>(fleet.rows(player.getUniqueId()))) {
            Optional<ShipDef> def = fleet.def(row.type());
            if (def.isEmpty()) {
                continue;
            }
            int max = ShipMath.maxDurability(def.get(), row.upgrades());
            ShipState state = fleet.isAfloat(row.shipId()) ? ShipState.AFLOAT : ShipState.STORED;
            fleet.replace(row.withDurability(max, state));
            Afloat a = fleet.afloat(row.shipId());
            if (a != null) {
                a.row = a.row.withDurability(max, ShipState.AFLOAT);
            }
            long id = row.shipId();
            var _ =
                    kit.api()
                            .database()
                            .asyncVoid(c -> ShipRepository.updateState(c, id, max, state));
        }
    }
}
