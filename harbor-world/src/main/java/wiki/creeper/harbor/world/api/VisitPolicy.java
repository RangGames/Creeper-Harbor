package wiki.creeper.harbor.world.api;

import wiki.creeper.harbor.common.model.Keyed;

/**
 * Housing visit policy (housing_plot.visit_policy). 'Friends' are taken to be the co-builder list
 * (docs 20 8.8 open question).
 */
public enum VisitPolicy implements Keyed {
    PUBLIC,
    MEMBERS,
    PRIVATE;

    @Override
    public String key() {
        return Keyed.keyOf(this);
    }
}
