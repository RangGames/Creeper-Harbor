package wiki.creeper.harbor.npc.logic;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.npc.content.CutsceneDef;
import wiki.creeper.harbor.npc.content.Spot;

/**
 * Cutscene keyframes to a teleport plan (no server API). The camera Display is interpolated by the
 * client via teleport_duration (max 59 ticks), so long segments are split into maxStep-tick steps
 * through intermediate positions.
 */
public final class CameraPlan {

    private CameraPlan() {}

    /**
     * One teleport.
     *
     * @param tick tick to send at
     * @param duration interpolation ticks (0 means instant)
     */
    public record Move(int tick, Spot target, int duration) {}

    public static List<Move> plan(List<CutsceneDef.CameraKey> keys, int maxStep) {
        List<Move> moves = new ArrayList<>();
        if (keys.isEmpty()) {
            return moves;
        }
        moves.add(new Move(0, keys.getFirst().pos(), 0));
        for (int i = 0; i + 1 < keys.size(); i++) {
            CutsceneDef.CameraKey from = keys.get(i);
            CutsceneDef.CameraKey to = keys.get(i + 1);
            int span = to.t() - from.t();
            for (int start = 0; start < span; start += maxStep) {
                int end = Math.min(span, start + maxStep);
                double f = (double) end / span;
                moves.add(new Move(from.t() + start, lerp(from.pos(), to.pos(), f), end - start));
            }
        }
        return moves;
    }

    /** Position at tick t (actor paths). Out of range gives the end values. */
    public static Spot at(List<CutsceneDef.CameraKey> keys, int t) {
        if (t <= keys.getFirst().t()) {
            return keys.getFirst().pos();
        }
        for (int i = 0; i + 1 < keys.size(); i++) {
            CutsceneDef.CameraKey from = keys.get(i);
            CutsceneDef.CameraKey to = keys.get(i + 1);
            if (t <= to.t()) {
                double f = (double) (t - from.t()) / (to.t() - from.t());
                return lerp(from.pos(), to.pos(), f);
            }
        }
        return keys.getLast().pos();
    }

    public static Spot lerp(Spot a, Spot b, double f) {
        return new Spot(
                a.world(),
                a.x() + (b.x() - a.x()) * f,
                a.y() + (b.y() - a.y()) * f,
                a.z() + (b.z() - a.z()) * f,
                lerpAngle(a.yaw(), b.yaw(), f),
                (float) (a.pitch() + (b.pitch() - a.pitch()) * f));
    }

    /** Angles turn the short way (350° → 10° is +20°). */
    static float lerpAngle(float a, float b, double f) {
        double diff = ((b - a) % 360 + 540) % 360 - 180;
        return (float) (a + diff * f);
    }
}
