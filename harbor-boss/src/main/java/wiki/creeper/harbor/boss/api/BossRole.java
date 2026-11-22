package wiki.creeper.harbor.boss.api;

import java.util.Optional;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.Keyed;
import wiki.creeper.harbor.core.api.PartyService;

/**
 * Boss fight role (docs 20 10.4 roles and contribution, '4. Fishing presentation & boss fights'
 * 2.1). Boss-only information (recommended line, etc.) lives here; as a party role it maps to
 * {@link #partyRole()}.
 */
public enum BossRole implements Keyed {
    /** Holds the boss with a fishing rod (tension). Recommended: gathering line. */
    ANCHOR,
    /** Throws harpoons (damage). Recommended: exploration line. */
    HARPOONER,
    /** Dodges charges at the helm (evasion). Recommended: exploration line. */
    CAPTAIN,
    /** Repairs damaged spots (repair). Recommended: production line. */
    MECHANIC,
    /**
     * Hands out buff food and gives a loot bonus (support amount is added to repair). Recommended:
     * economy line.
     */
    QUARTERMASTER;

    /**
     * Role recommended for a job line (assigned to players who did not pick one). Harpooner if
     * there is no line.
     */
    public static BossRole recommended(Optional<JobLine> line) {
        return line.map(
                        l ->
                                switch (l) {
                                    case COLLECT -> ANCHOR;
                                    case EXPLORE -> HARPOONER;
                                    case PRODUCE -> MECHANIC;
                                    case ECONOMY -> QUARTERMASTER;
                                })
                .orElse(HARPOONER);
    }

    /** Value used when mapping to the Core party role. */
    public PartyService.Role partyRole() {
        return switch (this) {
            case ANCHOR -> PartyService.Role.REELER;
            case HARPOONER -> PartyService.Role.HARPOONER;
            case CAPTAIN -> PartyService.Role.CAPTAIN;
            case MECHANIC -> PartyService.Role.MECHANIC;
            case QUARTERMASTER -> PartyService.Role.QUARTERMASTER;
        };
    }

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
