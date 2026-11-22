package wiki.creeper.harbor.common.content.type;

import wiki.creeper.harbor.common.id.HarborId;

/**
 * Temporary ID used to finish building a record after a parse error. Never escapes, since the whole
 * result is discarded when there are errors.
 */
final class Placeholder {

    static final HarborId FISH = HarborId.parse("fish/invalid");
    static final HarborId ITEM = HarborId.parse("item/invalid");
    static final HarborId REGION = HarborId.parse("region/invalid");

    private Placeholder() {}
}
