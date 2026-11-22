package wiki.creeper.harbor.common.rig;

import wiki.creeper.harbor.common.content.type.FishDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.RigId;

/**
 * Fish species → fish rig mapping (proposed). Fish rigs follow the resource pack model name: {@code
 * rig/fish/<model name>}. The fish ID and model name can differ (e.g. {@code
 * fish/calm_bay_gizzard_shad} → model {@code harbor:fish/calm_bay_shad}), so it is always derived
 * from FishDef.model(). Wildlife, Fishing and Collection use the same rule.
 */
public final class FishRigs {

    private static final String MODEL_PREFIX = "fish/";

    private FishRigs() {}

    /**
     * Fish rig ID: model {@code <ns>:fish/<name>} → {@code <ns>:rig/fish/<name>}. If the model key
     * is not in that form, the fish ID name is used.
     */
    public static RigId rigOf(FishDef fish) {
        String model = fish.model();
        try {
            HarborId key = HarborId.parse(model);
            if (key.path().startsWith(MODEL_PREFIX)
                    && key.path().length() > MODEL_PREFIX.length()) {
                return new RigId(new HarborId(key.namespace(), "rig/" + key.path()));
            }
        } catch (IllegalArgumentException e) {
            // Model key is not in HarborId form: fall back to the fish ID
        }
        return byId(fish);
    }

    /** Old rule (fish ID name). Used only when the rig {@link #rigOf} points at does not exist. */
    public static RigId byId(FishDef fish) {
        HarborId id = fish.id().id();
        return new RigId(new HarborId(id.namespace(), "rig/" + MODEL_PREFIX + id.name()));
    }

    /** Midpoint of the fish size range (blocks, 1 block = 1 m). 0.4 if there is no range. */
    public static double naturalLength(FishDef fish) {
        double cm = (fish.sizeMin() + fish.sizeMax()) / 2;
        return cm > 0 ? cm / 100.0 : 0.4;
    }

    /**
     * Instance scale that draws the rig at body length lengthBlocks (blocks). 1 (the rig's original
     * size) if the rig has no length. Same as the resource pack guide's "scale = size (m) ÷
     * model_length_blocks" (also accounts for the rig scale).
     */
    public static double fitScale(RigDef rig, double lengthBlocks) {
        double own = rig.length() * rig.scale();
        if (own <= 0 || lengthBlocks <= 0) {
            return 1.0;
        }
        return lengthBlocks / own;
    }
}
