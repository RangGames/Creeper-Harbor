package wiki.creeper.harbor.core.rig;

import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.rig.RigBaker;
import wiki.creeper.harbor.common.rig.Xform;

/**
 * Rig playback settings (balance.yml rig section). Defaults are used only when balance.yml has no
 * value.
 *
 * @param moveTicks default interpolation ticks for movement and rotation (teleport_duration)
 * @param blendTicks blend ticks when switching animations
 * @param lodDistance skip animation updates when the nearest viewer is farther than this (blocks)
 * @param tickBudgetNanos rig update budget per tick. Once exceeded, remaining rigs wait for the
 *     next tick (round robin)
 * @param hitboxInterval hitbox position update interval (ticks)
 * @param partBudget display part budget around one player ('16. Plugin design' chapter 7). /harbor
 *     rig stats warns when exceeded
 */
record RigSettings(
        RigBaker.Settings bake,
        int moveTicks,
        int blendTicks,
        double lodDistance,
        long tickBudgetNanos,
        int hitboxInterval,
        int partBudget) {

    static RigSettings from(Balance b) {
        int fps = b.integer("rig.fps", 10);
        if (fps <= 0 || 20 % fps != 0) {
            fps = 10; // CoreContent validation rejects this, but don't stall if an unchecked
            // value gets in
        }
        Xform.Tolerance tolerance =
                new Xform.Tolerance(
                        b.number("rig.compress.position", 0.005),
                        b.number("rig.compress.rotation", 1.2),
                        b.number("rig.compress.scale", 0.005));
        int maxGap = Math.max(1, b.integer("rig.compress.max_gap", 8));
        return new RigSettings(
                new RigBaker.Settings(fps, tolerance, maxGap),
                Math.max(0, b.integer("rig.move_ticks", 2)),
                Math.max(0, b.integer("rig.blend_ticks", 4)),
                Math.max(1, b.number("rig.lod_distance", 48)),
                (long) (Math.max(0.05, b.number("rig.tick_budget_ms", 1.0)) * 1_000_000),
                Math.max(1, b.integer("rig.hitbox_interval", 2)),
                Math.max(1, b.integer("rig.part_budget", 300)));
    }
}
