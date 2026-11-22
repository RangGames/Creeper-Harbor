package wiki.creeper.harbor.npc.api;

import wiki.creeper.harbor.common.content.ContentSnapshot;

/**
 * Argument checker for conditions and actions (content check phase). Any error rejects the reload.
 */
public interface ArgCheck {

    /** The snapshot about to be built (for checking that quest IDs exist, etc.). */
    ContentSnapshot snapshot();

    /** Records an error. The dialog file, node and choice position are attached. */
    void error(String message);
}
