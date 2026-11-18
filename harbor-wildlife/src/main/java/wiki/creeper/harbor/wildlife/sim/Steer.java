package wiki.creeper.harbor.wildlife.sim;

import java.util.List;
import java.util.random.RandomGenerator;
import wiki.creeper.harbor.common.rig.Vec3;
import wiki.creeper.harbor.wildlife.api.Medium;

/**
 * 자체 조향 계산 ('17-3' 9.4 뇌 방식 결정). 서버 API 없는 순수 함수라 단위 테스트한다. 단위는 블록·초·라디안 (도는 이름에 Deg).
 *
 * <ul>
 *   <li>목표 추종: seek · arrive · flee · wander
 *   <li>무리: separation · alignment · cohesion
 *   <li>매질: 탐침(앞·위·아래·좌·우 5점) 회피, 수면 아래 유지, 높이 띠, 매질 밖으로 나가지 않는 이동
 *   <li>방향: 최대 회전 속도 제한, 진행 방향 → 마인크래프트 yaw·pitch
 * </ul>
 */
public final class Steer {

    private static final double EPS = 1e-9;
    private static final Vec3 UP = new Vec3(0, 1, 0);

    private Steer() {}

    // ------------------------------------------------------------------ 벡터 도우미

    public static double length(Vec3 v) {
        return Math.sqrt(v.x() * v.x() + v.y() * v.y() + v.z() * v.z());
    }

    public static double horizontal(Vec3 v) {
        return Math.sqrt(v.x() * v.x() + v.z() * v.z());
    }

    public static Vec3 normalize(Vec3 v) {
        double len = length(v);
        return len < EPS ? Vec3.ZERO : v.mul(1 / len);
    }

    public static Vec3 clampLength(Vec3 v, double max) {
        double len = length(v);
        return len > max && len > EPS ? v.mul(max / len) : v;
    }

    public static double distanceSq(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x();
        double dy = a.y() - b.y();
        double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** 두 방향 사이 각 (라디안). 0벡터가 있으면 0. */
    public static double angle(Vec3 a, Vec3 b) {
        Vec3 na = normalize(a);
        Vec3 nb = normalize(b);
        if (na.equals(Vec3.ZERO) || nb.equals(Vec3.ZERO)) {
            return 0;
        }
        return Math.acos(Math.clamp(na.dot(nb), -1.0, 1.0));
    }

    /** 현재 방향을 원하는 방향 쪽으로 최대 maxAngle만큼 돌린 단위 벡터 (로드리게스 회전). 반대 방향이면 위쪽 축(수평 회전)으로 돈다. */
    public static Vec3 rotateToward(Vec3 current, Vec3 desired, double maxAngle) {
        Vec3 c = normalize(current);
        Vec3 d = normalize(desired);
        if (d.equals(Vec3.ZERO)) {
            return c;
        }
        if (c.equals(Vec3.ZERO)) {
            return d;
        }
        double theta = Math.acos(Math.clamp(c.dot(d), -1.0, 1.0));
        if (theta <= maxAngle) {
            return d;
        }
        Vec3 axis = c.cross(d);
        if (length(axis) < 1e-6) {
            // 정반대: 수평으로 돌도록 위쪽 축 (진행 방향이 수직이면 X축)
            axis = Math.abs(c.y()) < 0.99 ? UP : new Vec3(1, 0, 0);
        }
        Vec3 k = normalize(axis);
        double cos = Math.cos(maxAngle);
        double sin = Math.sin(maxAngle);
        Vec3 rotated = c.mul(cos).add(k.cross(c).mul(sin)).add(k.mul(k.dot(c) * (1 - cos)));
        return normalize(rotated);
    }

    /** 진행 방향 → 마인크래프트 yaw (도, 남쪽 +Z가 0, 서쪽 −X가 90). 수평 성분이 없으면 fallback. */
    public static float yawDeg(Vec3 dir, float fallback) {
        if (horizontal(dir) < 1e-6) {
            return fallback;
        }
        return (float) Math.toDegrees(Math.atan2(-dir.x(), dir.z()));
    }

    /**
     * 기울기 = atan2(수직 속도, 수평 속도), ±limit로 제한 ('17-3' 9.4). 마인크래프트 pitch는 아래가 +라서 부호를 뒤집는다 (오르면 음수).
     * 모델 머리 방향 보정은 Core setHeading이 한다.
     */
    public static float pitchDeg(Vec3 velocity, double limitDeg) {
        double h = horizontal(velocity);
        if (h < 1e-6 && Math.abs(velocity.y()) < 1e-6) {
            return 0;
        }
        double deg = Math.toDegrees(Math.atan2(velocity.y(), h));
        return (float) -Math.clamp(deg, -limitDeg, limitDeg);
    }

    // ------------------------------------------------------------------ 목표 추종

    public static Vec3 seek(Vec3 pos, Vec3 target, double speed) {
        return normalize(target.sub(pos)).mul(speed);
    }

    /** 목표 근처(slowRadius 안)에서 속도를 줄인다. */
    public static Vec3 arrive(Vec3 pos, Vec3 target, double speed, double slowRadius) {
        Vec3 to = target.sub(pos);
        double d = length(to);
        if (d < EPS) {
            return Vec3.ZERO;
        }
        double s = d < slowRadius ? speed * (d / slowRadius) : speed;
        return to.mul(s / d);
    }

    public static Vec3 flee(Vec3 pos, Vec3 threat, double speed) {
        Vec3 away = pos.sub(threat);
        if (length(away) < EPS) {
            away = new Vec3(1, 0, 0);
        }
        return normalize(away).mul(speed);
    }

    /** 지금 방향을 수평 ±yawJitter, 수직 ±pitchJitter 안에서 무작위로 튼다 (라디안). */
    public static Vec3 wander(
            Vec3 heading, double yawJitter, double pitchJitter, RandomGenerator random) {
        Vec3 h = normalize(heading);
        if (h.equals(Vec3.ZERO)) {
            h = new Vec3(0, 0, 1);
        }
        double yaw = Math.atan2(h.x(), h.z()) + (random.nextDouble() * 2 - 1) * yawJitter;
        double pitch =
                Math.clamp(
                        Math.asin(Math.clamp(h.y(), -1.0, 1.0))
                                + (random.nextDouble() * 2 - 1) * pitchJitter,
                        -1.2,
                        1.2);
        double c = Math.cos(pitch);
        return new Vec3(Math.sin(yaw) * c, Math.sin(pitch), Math.cos(yaw) * c);
    }

    // ------------------------------------------------------------------ 무리

    /** 가까운 이웃에게서 멀어지는 방향 (가까울수록 강하게). minDist 밖 이웃은 무시. */
    public static Vec3 separation(Vec3 pos, List<Vec3> neighbors, double minDist) {
        Vec3 sum = Vec3.ZERO;
        double min2 = minDist * minDist;
        for (Vec3 n : neighbors) {
            Vec3 away = pos.sub(n);
            double d2 = away.dot(away);
            if (d2 < EPS || d2 > min2) {
                continue;
            }
            sum = sum.add(away.mul(1 / d2));
        }
        return normalize(sum);
    }

    /** 이웃 평균 진행 방향. */
    public static Vec3 alignment(List<Vec3> neighborVelocities) {
        Vec3 sum = Vec3.ZERO;
        for (Vec3 v : neighborVelocities) {
            sum = sum.add(normalize(v));
        }
        return normalize(sum);
    }

    /** 이웃 중심 쪽. */
    public static Vec3 cohesion(Vec3 pos, List<Vec3> neighbors) {
        if (neighbors.isEmpty()) {
            return Vec3.ZERO;
        }
        Vec3 sum = Vec3.ZERO;
        for (Vec3 n : neighbors) {
            sum = sum.add(n);
        }
        return normalize(sum.mul(1.0 / neighbors.size()).sub(pos));
    }

    // ------------------------------------------------------------------ 매질

    /**
     * 매질 탐침 회피 ('17-3' 9.4: 앞·위·아래·좌·우 5점, 앞은 속도 × 1.5초 거리). 막힌 탐침의 반대쪽으로 미는 벡터를 돌려준다. 앞이 막혔으면 열린 옆
     * 방향 중 하나로 크게 꺾는다.
     *
     * @param ahead 앞 탐침 거리 (블록)
     * @param side 옆·위·아래 탐침 거리 (블록)
     */
    public static Vec3 avoid(
            MediumField field, Medium medium, Vec3 pos, Vec3 heading, double ahead, double side) {
        Vec3 f = normalize(heading);
        if (f.equals(Vec3.ZERO)) {
            return Vec3.ZERO;
        }
        Vec3 right = normalize(f.cross(UP));
        if (right.equals(Vec3.ZERO)) {
            right = new Vec3(1, 0, 0);
        }
        Vec3 push = Vec3.ZERO;
        boolean frontBlocked =
                !open(field, medium, pos.add(f.mul(ahead)))
                        || !open(field, medium, pos.add(f.mul(Math.min(1.0, ahead))));
        boolean upBlocked = !open(field, medium, pos.add(UP.mul(side)));
        boolean downBlocked = !open(field, medium, pos.sub(UP.mul(side)));
        boolean rightBlocked = !open(field, medium, pos.add(right.mul(side)));
        boolean leftBlocked = !open(field, medium, pos.sub(right.mul(side)));
        if (upBlocked) {
            push = push.sub(UP);
        }
        if (downBlocked) {
            push = push.add(UP);
        }
        if (rightBlocked) {
            push = push.sub(right);
        }
        if (leftBlocked) {
            push = push.add(right);
        }
        if (frontBlocked) {
            // 열린 쪽으로 꺾는다: 좌·우·위·아래 순으로 앞쪽 대각 탐침을 본다
            Vec3[] options = {
                f.add(right).mul(0.5), f.sub(right).mul(0.5), f.add(UP).mul(0.5), f.sub(UP).mul(0.5)
            };
            Vec3 best = null;
            for (Vec3 o : options) {
                if (open(field, medium, pos.add(normalize(o).mul(Math.min(ahead, 2.0))))) {
                    best = o;
                    break;
                }
            }
            push = push.add(best != null ? normalize(best).mul(2.0) : f.mul(-2.0));
        }
        return push;
    }

    public static boolean open(MediumField field, Medium medium, Vec3 p) {
        return field.is(medium, p.x(), p.y(), p.z());
    }

    /** 수면 아래 gap 블록 유지 (물·용암). 위로 gap만큼 올라간 자리가 매질 밖이면 아래로 미는 값 (블록/초), 아니면 0. 공중·바닥은 0. */
    public static double surfaceKeep(MediumField field, Medium medium, Vec3 pos, double gap) {
        if (medium != Medium.WATER && medium != Medium.LAVA) {
            return 0;
        }
        return open(field, medium, pos.add(new Vec3(0, gap, 0))) ? 0 : -1.0;
    }

    /** 높이 띠 [lo, hi] 밖이면 띠 쪽으로 미는 수직 값 (블록/초). */
    public static double band(double y, double lo, double hi) {
        if (y < lo) {
            return Math.min(1.0, lo - y);
        }
        if (y > hi) {
            return -Math.min(1.0, y - hi);
        }
        return 0;
    }

    /** 수면 높이 (pos 위로 매질이 끝나는 첫 칸의 바닥 y). maxScan 안에서 못 찾으면 pos.y + maxScan. */
    public static double surfaceY(MediumField field, Medium medium, Vec3 pos, int maxScan) {
        int x = MediumField.floor(pos.x());
        int z = MediumField.floor(pos.z());
        int y0 = MediumField.floor(pos.y());
        for (int y = y0; y <= y0 + maxScan; y++) {
            if (!field.is(medium, x, y, z)) {
                return y;
            }
        }
        return y0 + maxScan;
    }

    /** 결과 위치와, 원래 이동이 막혔는지. */
    public record Move(Vec3 pos, boolean blocked) {}

    /**
     * 매질 안에서만 움직인다 ('17-3' 9.6 용암·공중 어종이 매질 밖으로 나가지 않음). 전체 이동이 막히면 축별로 미끄러지고(수평 → 수직), 그래도 막히면
     * 제자리.
     */
    public static Move move(MediumField field, Medium medium, Vec3 pos, Vec3 velocity, double dt) {
        Vec3 delta = velocity.mul(dt);
        Vec3 full = pos.add(delta);
        if (open(field, medium, full)) {
            return new Move(full, false);
        }
        Vec3[] slides = {
            pos.add(new Vec3(delta.x(), 0, delta.z())),
            pos.add(new Vec3(delta.x(), 0, 0)),
            pos.add(new Vec3(0, 0, delta.z())),
            pos.add(new Vec3(0, delta.y(), 0))
        };
        for (Vec3 s : slides) {
            if (!s.equals(pos) && open(field, medium, s)) {
                return new Move(s, true);
            }
        }
        return new Move(pos, true);
    }
}
