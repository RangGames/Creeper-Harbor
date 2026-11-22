package wiki.creeper.harbor.npc.api;

/** Why a dialog session ended. */
public enum DialogEndReason {
    /** Close button, close action, or a choice without goto. */
    CLOSED,
    /** An action opened another screen (Handoff). */
    HANDOFF,
    /** Replaced by another dialog being opened. */
    REPLACED,
    /** Over 5 minutes, or moved away from the NPC. */
    EXPIRED,
    QUIT
}
