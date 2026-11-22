package wiki.creeper.harbor.common.rig;

/**
 * Translation/rotation/scale transform (immutable). Applied to a point p: translation +
 * rotation(scale ⊙ p).
 *
 * <p>Hierarchical composition ({@link #then}) drops shear, as common game engines do. It maps
 * directly onto a Display entity transform (translation · left rotation · scale · right rotation).
 */
public record Xform(Vec3 translation, Quat rotation, Vec3 scale) {

    public static final Xform IDENTITY = new Xform(Vec3.ZERO, Quat.IDENTITY, Vec3.ONE);

    public static Xform uniform(double k) {
        return new Xform(Vec3.ZERO, Quat.IDENTITY, new Vec3(k, k, k));
    }

    /** Result of placing child inside this (parent) transform (parent ∘ child). */
    public Xform then(Xform child) {
        return new Xform(
                translation.add(rotation.rotate(scale.mul(child.translation))),
                rotation.mul(child.rotation).normalize(),
                scale.mul(child.scale));
    }

    public Vec3 apply(Vec3 point) {
        return translation.add(rotation.rotate(scale.mul(point)));
    }

    /** Same as client interpolation: linear for translation/scale, spherical for rotation. */
    public static Xform interpolate(Xform a, Xform b, double f) {
        return new Xform(
                Vec3.lerp(a.translation, b.translation, f),
                Quat.slerp(a.rotation, b.rotation, f),
                Vec3.lerp(a.scale, b.scale, f));
    }

    /** Whether two transforms are equal within tolerance (for keyframe compression). */
    public boolean near(Xform o, Tolerance tol) {
        return translation.distance(o.translation) <= tol.position()
                && rotation.angleTo(o.rotation) <= tol.rotationDeg()
                && scale.maxDiff(o.scale) <= tol.scale();
    }

    /**
     * Keyframe compression tolerance.
     *
     * @param position translation (blocks)
     * @param rotationDeg rotation (degrees)
     * @param scale scale (factor)
     */
    public record Tolerance(double position, double rotationDeg, double scale) {}
}
