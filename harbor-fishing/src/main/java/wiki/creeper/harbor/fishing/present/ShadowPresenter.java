package wiki.creeper.harbor.fishing.present;

import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.fishing.content.FishingPresets;

/**
 * Fish shadow and jump presentation (docs 20 3.4 "shadow · jump presentation"). The docs use Core
 * RigInstance (species rig, shown only to the caster), but since it did not exist yet it sits
 * behind this interface and the default implementation ({@link DisplayShadowPresenter}) draws a
 * single ItemDisplay. Once RigInstance exists, only the implementation of this interface changes
 * (rig integration point). Main thread only.
 */
public interface ShadowPresenter {

    /**
     * Creates a shadow (visible only to the caster).
     *
     * @param at initial location (underwater)
     * @param preset grade presentation (default shadow model, size, glow color)
     * @param gradeGlow grade-colored outline when shadow identification (feature_shadow_grade) is
     *     on
     */
    Shadow spawn(
            Player viewer,
            Location at,
            FishDef fish,
            FishingPresets.GradePreset preset,
            boolean gradeGlow);

    /** One shadow. */
    interface Shadow {

        Location location();

        /** Bite: the shadow dives under the bobber (rig bite animation). */
        default void bite() {}

        /** Interpolated move (teleport_duration, up to 59 ticks). */
        void moveTo(Location target, int ticks);

        /**
         * Jump pattern: leaps above the surface and comes back. extraViewers briefly see only this
         * scene.
         */
        void jump(Collection<Player> extraViewers);

        boolean isValid();

        void remove();
    }
}
