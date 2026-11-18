package wiki.creeper.harbor.common.rig;

/**
 * 이동·회전·크기 변환 (변경 불가). 점 p에 적용하면 translation + rotation(scale ⊙ p).
 *
 * <p>계층 합성({@link #then})은 전단(shear)을 버리는 일반적인 게임 엔진 방식이다. Display 엔티티의 변환(이동 · 왼쪽 회전 · 크기 · 오른쪽
 * 회전)에 그대로 들어간다.
 */
public record Xform(Vec3 translation, Quat rotation, Vec3 scale) {

    public static final Xform IDENTITY = new Xform(Vec3.ZERO, Quat.IDENTITY, Vec3.ONE);

    public static Xform uniform(double k) {
        return new Xform(Vec3.ZERO, Quat.IDENTITY, new Vec3(k, k, k));
    }

    /** 이 변환(부모) 안에 child를 놓은 결과 (부모 ∘ 자식). */
    public Xform then(Xform child) {
        return new Xform(
                translation.add(rotation.rotate(scale.mul(child.translation))),
                rotation.mul(child.rotation).normalize(),
                scale.mul(child.scale));
    }

    public Vec3 apply(Vec3 point) {
        return translation.add(rotation.rotate(scale.mul(point)));
    }

    /** 클라이언트 보간과 같은 방식: 이동·크기는 선형, 회전은 구면 보간. */
    public static Xform interpolate(Xform a, Xform b, double f) {
        return new Xform(
                Vec3.lerp(a.translation, b.translation, f),
                Quat.slerp(a.rotation, b.rotation, f),
                Vec3.lerp(a.scale, b.scale, f));
    }

    /** 두 변환이 허용 오차 안에서 같은지 (키프레임 압축용). */
    public boolean near(Xform o, Tolerance tol) {
        return translation.distance(o.translation) <= tol.position()
                && rotation.angleTo(o.rotation) <= tol.rotationDeg()
                && scale.maxDiff(o.scale) <= tol.scale();
    }

    /**
     * 키프레임 압축 허용 오차.
     *
     * @param position 이동 (블록)
     * @param rotationDeg 회전 (도)
     * @param scale 크기 (배율)
     */
    public record Tolerance(double position, double rotationDeg, double scale) {}
}
