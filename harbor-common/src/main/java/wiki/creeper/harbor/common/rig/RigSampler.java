package wiki.creeper.harbor.common.rig;

import org.jspecify.annotations.Nullable;

/** 리그 자세 계산 (순수 계산, 서버 API 없음). 동작의 시각 t에서 뼈마다 리그 원점 기준 변환을 구한다. */
public final class RigSampler {

    private RigSampler() {}

    /**
     * 시각 t의 뼈 변환 (리그 원점 기준, 리그 scale 포함). 동작이 없으면 기본 자세.
     *
     * @return 뼈 번호 순서의 배열
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

    /** 뼈 하나의 부모 기준 변환. */
    static Xform local(RigDef.Bone bone, RigDef.@Nullable BoneTracks tracks, double t) {
        if (tracks == null) {
            return new Xform(bone.pivot(), Quat.euler(bone.rotation()), bone.scale());
        }
        Vec3 position = bone.pivot().add(tracks.position().sample(t, Vec3.ZERO));
        Vec3 euler = bone.rotation().add(tracks.rotation().sample(t, Vec3.ZERO));
        Vec3 scale = bone.scale().mul(tracks.scale().sample(t, Vec3.ONE));
        return new Xform(position, Quat.euler(euler), scale);
    }

    /** 부품의 Display 변환 (오른쪽 회전 보정은 따로). */
    public static Xform part(Xform boneWorld, RigDef.Part part) {
        return boneWorld.then(part.offset());
    }

    /** 판정 상자 중심 (리그 원점 기준). */
    public static Vec3 hitboxCenter(Xform boneWorld, RigDef.Hitbox hitbox) {
        return boneWorld.apply(hitbox.offset());
    }

    /**
     * 진행 방향(yaw·pitch, 마인크래프트 기준)을 엔티티에 줄 회전으로 바꾼다. 머리가 −Z인 모델은 yaw + 180°, pitch 부호 반대 (시제품 보정,
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

    /** −180 ≤ 각 < 180. */
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
