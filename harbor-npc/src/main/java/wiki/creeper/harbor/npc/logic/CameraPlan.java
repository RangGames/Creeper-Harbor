package wiki.creeper.harbor.npc.logic;

import java.util.ArrayList;
import java.util.List;
import wiki.creeper.harbor.npc.content.CutsceneDef;
import wiki.creeper.harbor.npc.content.Spot;

/**
 * 컷씬 키프레임 → 텔레포트 계획 (서버 API 없음). 카메라 Display는 teleport_duration(최대 59틱)으로 클라이언트가 보간하므로, 긴 구간은
 * maxStep 틱 단위로 나눠 중간 위치로 옮긴다.
 */
public final class CameraPlan {

    private CameraPlan() {}

    /**
     * 텔레포트 한 번.
     *
     * @param tick 보낼 틱
     * @param duration 보간 틱 (0이면 즉시)
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

    /** 틱 t의 위치 (배우 경로). 범위 밖이면 양 끝 값. */
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

    /** 각도는 짧은 쪽으로 돈다 (350° → 10°는 +20°). */
    static float lerpAngle(float a, float b, double f) {
        double diff = ((b - a) % 360 + 540) % 360 - 180;
        return (float) (a + diff * f);
    }
}
