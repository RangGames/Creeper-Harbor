package wiki.creeper.harbor.common.rig;

import org.jspecify.annotations.Nullable;

/**
 * Rig pose computation (pure computation, no server API). Computes each bone's transform relative
 * to the rig origin at time t of an animation.
 */
public final class RigSampler {

    private RigSampler() {}

    /**
     * Bone transforms at time t (relative to the rig origin, including rig scale). Rest pose if
     * there is no animation.
     *
     * @return array in bone index order
     */
    public static Xform[] pose(RigDef rig, RigDef.@Nullable Animation animation, double t) {
        int n = rig.bones().size();
        Xform[] world = new Xform[n];
        Xform origin = Xform.uniform(rig.scale());
        for (int i = 0; i < n; i++) {
            RigDef.Bone bone = rig.bones().get(i);
            Xform local = local(bone, animation == null ? null : animation.tracks().get(i), t);
            Xform parent = bone.parent() < 0 ? origin : world[bone.parent()];
            world[i] = parent.then(local);
        }
        return world;
    }

    /** Transform of one bone relative to its parent. */
    static Xform local(RigDef.Bone bone, RigDef.@Nullable BoneTracks tracks, double t) {
        if (tracks == null) {
            return new Xform(bone.pivot(), Quat.euler(bone.rotation()), bone.scale());
        }
        Vec3 position = bone.pivot().add(tracks.position().sample(t, Vec3.ZERO));
        Vec3 euler = bone.rotation().add(tracks.rotation().sample(t, Vec3.ZERO));
        Vec3 scale = bone.scale().mul(tracks.scale().sample(t, Vec3.ONE));
        return new Xform(position, Quat.euler(euler), scale);
    }

    /** Display transform of a part (right-rotation correction is separate). */
    public static Xform part(Xform boneWorld, RigDef.Part part) {
        return boneWorld.then(part.offset());
    }

    /** Hitbox center (relative to the rig origin). */
    public static Vec3 hitboxCenter(Xform boneWorld, RigDef.Hitbox hitbox) {
        return boneWorld.apply(hitbox.offset());
    }

    /**
     * Converts a heading (yaw/pitch, Minecraft convention) into the rotation given to the entity.
     * Models whose head faces −Z get yaw + 180° and a flipped pitch sign (prototype correction,
     * '17-3' 9.4).
     *
     * @return [yaw, pitch]
     */
    public static float[] entityHeading(RigDef rig, float yaw, float pitch) {
        if (rig.forwardNegZ()) {
            return new float[] {wrapDegrees(yaw + 180f), -pitch};
        }
        return new float[] {wrapDegrees(yaw), pitch};
    }

    /** −180 ≤ angle < 180. */
    public static float wrapDegrees(float degrees) {
        float d = degrees % 360f;
        if (d >= 180f) {
            d -= 360f;
        }
        if (d < -180f) {
            d += 360f;
        }
        return d;
    }
}
