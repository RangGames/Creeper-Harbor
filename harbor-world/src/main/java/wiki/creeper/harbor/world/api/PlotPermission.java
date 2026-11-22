package wiki.creeper.harbor.world.api;

/** Housing plot permissions (docs 20 8.4: owner, co-builders, visitors). */
public enum PlotPermission {
    /** Enter (visit policy). */
    VISIT,
    /** Place and break blocks and decor (owner, co-builders). */
    BUILD,
    /** Edit the personal aquarium display (owner, co-builders; asked by Collection). */
    EDIT_DISPLAY,
    /** Set the visit policy and co-builders (owner only). */
    MANAGE
}
