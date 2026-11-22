package wiki.creeper.harbor.wildlife.content;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * Rig template ('17-3' 9.3 distribution: fish 128, ceph 6, ray 6, seahorse 2, jelly 2, clam 1).
 * Picks the default max turn rate and tilt limit, and draws with the rig/wild/&lt;template&gt; rig
 * when the species has no rig.
 */
public enum WildTemplate implements Keyed {
    FISH,
    CEPH,
    RAY,
    SEAHORSE,
    JELLY,
    CLAM;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
