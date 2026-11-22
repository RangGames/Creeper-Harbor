package wiki.creeper.harbor.common.content;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.util.SourcePos;

/**
 * Collects references to other content during parsing. They are checked all at once after every
 * file is read.
 */
public final class ParseContext {

    /** One reference: which ID of which type was referenced from where. */
    public record Reference(String targetType, HarborId id, SourcePos pos) {}

    private final List<Reference> references = new ArrayList<>();

    /**
     * Registers a reference. If the target type is not registered with the loader (that module is
     * not present yet), the check is skipped.
     *
     * @param targetType target type name (e.g. {@code region})
     */
    public void ref(String targetType, HarborId id, SourcePos pos) {
        references.add(new Reference(targetType, id, pos));
    }

    List<Reference> references() {
        return Collections.unmodifiableList(references);
    }
}
