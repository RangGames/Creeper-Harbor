package wiki.creeper.harbor.fishing.api;

/**
 * Result of a {@link TrapService} action (place · harvest · add bait · remove).
 *
 * @param trapId the trap the action used (the new trap ID for a placement, 0 if none)
 * @param amount harvest: fish taken out · add bait: bait put in · remove: bait returned (0
 *     otherwise)
 */
public record TrapAction(Outcome outcome, long trapId, int amount) {

    public boolean ok() {
        return outcome == Outcome.OK;
    }

    public static TrapAction of(Outcome outcome) {
        return new TrapAction(outcome, 0, 0);
    }

    public static TrapAction of(Outcome outcome, long trapId) {
        return new TrapAction(outcome, trapId, 0);
    }

    /** Outcome of a trap action. */
    public enum Outcome {
        OK,
        /** No such trap (or it was retrieved in the meantime). */
        NOT_FOUND,
        /** The trap belongs to another player. */
        NOT_OWNER,
        /** Placement: not near a buoy spot of a region trap zone. */
        NOT_ZONE,
        /** Placement: the buoy spot is not air or water. */
        BLOCKED,
        /** Placement: deep traps need the feature_deep_trap skill. */
        LOCKED,
        /** Placement: trap count limit reached. */
        LIMIT,
        /** Placement: another trap already sits on that spot. */
        OCCUPIED,
        /** Placement: not a trap item, or the player has none in the inventory. */
        NO_ITEM,
        /** Harvest: no free inventory slot. */
        INVENTORY_FULL,
        /** Harvest: nothing stored. */
        EMPTY,
        /** Add bait: the trap is already full of bait. */
        BAIT_FULL,
        /** Add bait: no usable bait in the inventory. */
        NO_BAIT,
        /** Remove: catches are still stored (harvest first). */
        NOT_EMPTY,
        /** DB error, or the player is in the middle of a server move. */
        FAILED
    }
}
