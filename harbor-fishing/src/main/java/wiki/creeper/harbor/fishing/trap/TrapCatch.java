package wiki.creeper.harbor.fishing.trap;

/**
 * One unharvested catch in a trap (traps.contents JSON entry, proposed).
 *
 * @param uid entry ID (prevents duplicate delivery on harvest or mail retrieval)
 * @param fish species ID string
 * @param caughtAt yield time (epoch ms)
 */
public record TrapCatch(String uid, String fish, double size, int quality, long caughtAt) {}
