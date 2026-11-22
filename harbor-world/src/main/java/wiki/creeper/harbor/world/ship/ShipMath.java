package wiki.creeper.harbor.world.ship;

import java.util.Map;
import wiki.creeper.harbor.world.api.UpgradePath;
import wiki.creeper.harbor.world.content.ShipDef;

/**
 * Ship stat calculations (docs 20 8.4 'ships'). No server API (unit tests). All numbers come from
 * ships.yml and balance.yml.
 */
public final class ShipMath {

    private ShipMath() {}

    /** Max durability = base × (1 + hull upgrade). */
    public static int maxDurability(ShipDef def, Map<UpgradePath, Integer> upgrades) {
        double hull =
                def.upgradeValue(UpgradePath.HULL, upgrades.getOrDefault(UpgradePath.HULL, 0));
        return (int) Math.round(def.durability() * (1 + hull));
    }

    /** Cargo slots (multiple of 9, at most 54). */
    public static int cargo(ShipDef def, Map<UpgradePath, Integer> upgrades) {
        double extra =
                def.upgradeValue(UpgradePath.CARGO, upgrades.getOrDefault(UpgradePath.CARGO, 0));
        int slots = def.cargo() + (int) Math.round(extra);
        return Math.min(ShipDef.MAX_CARGO, (slots / 9) * 9);
    }

    /** Ship speed multiplier (before stat modifiers) = 1 + ship speed + speed upgrade. */
    public static double baseSpeed(ShipDef def, Map<UpgradePath, Integer> upgrades) {
        return 1
                + def.speed()
                + def.upgradeValue(UpgradePath.SPEED, upgrades.getOrDefault(UpgradePath.SPEED, 0));
    }

    /**
     * Final speed multiplier = ship multiplier × boat_speed stat × storm multiplier.
     *
     * @param boatSpeed boat_speed stat (default 1.0, exploration automatic passive +10% · tailwind
     *     +5%/rank)
     * @param stormMult events.yml storm.speed_mult during a storm (1 with storm sailing rank 1)
     */
    public static double speed(double base, double boatSpeed, double stormMult) {
        return Math.max(0.1, base * boatSpeed * stormMult);
    }

    /**
     * A vanilla boat's terminal speed on water is proportional to its acceleration a, so adding a ×
     * (multiplier − 1) forward each tick changes the terminal speed by the multiplier (periodic
     * speed correction, Phase 1 verification item).
     */
    public static double impulse(double accel, double multiplier) {
        return accel * (multiplier - 1);
    }

    /** Cost of the next upgrade step = ship price × step ratio (10 · 20 · 30%). */
    public static long upgradeCost(ShipDef def, double ratio) {
        return Math.round(def.price() * ratio);
    }

    /**
     * Storm durability damage (every 5 seconds) = damage × boat_damage stat.
     *
     * @param boatDamage boat_damage stat (default 1.0, exploration automatic passive −0.10, sturdy
     *     hull −0.08/rank)
     */
    public static double stormDamage(int damage, double boatDamage) {
        return Math.max(0, damage * boatDamage);
    }

    /** Repair cost = ship price × ratio × repair_cost stat (at least 1, free if the price is 0). */
    public static long repairCost(long price, double rate, double repairCostStat) {
        if (price <= 0) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(price * rate * repairCostStat));
    }
}
