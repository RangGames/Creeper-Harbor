package wiki.creeper.harbor.common.content;

import java.util.List;
import java.util.function.Function;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * Content type. Each module defines its own types and registers them with Core's loader (e.g.
 * Fishing's fish, Quest's quest).
 *
 * <ul>
 *   <li>{@link Entries}: a type whose files hold a list of entries with IDs (fish/*.yml,
 *       items/*.yml, regions.yml)
 *   <li>{@link Single}: a type where one whole file is one value (balance.yml, messages_ko.yml)
 * </ul>
 */
public sealed interface ContentType<T> permits ContentType.Entries, ContentType.Single {

    /** Type name. Also used to point at the target type in reference checks. E.g. {@code fish}. */
    String name();

    /**
     * File pattern relative to the content folder (glob, '/'-separated). E.g. {@code fish/*.yml}.
     */
    List<String> files();

    /**
     * Converts one entry to a record. Errors are recorded on the node and references are registered
     * on ctx.
     */
    @FunctionalInterface
    interface Parser<T> {
        T parse(YamlNode node, ParseContext ctx);
    }

    /**
     * Type of entries with IDs. The file's top level is a list of entries, and each entry must have
     * an {@code id}.
     *
     * @param idPrefix ID path prefix (e.g. {@code fish/}). IDs with another prefix are errors.
     * @param idOf function that extracts the ID from a record
     */
    record Entries<T>(
            String name,
            List<String> files,
            String idPrefix,
            Parser<T> parser,
            Function<T, HarborId> idOf)
            implements ContentType<T> {

        public Entries {
            files = List.copyOf(files);
        }
    }

    /**
     * Single-file type.
     *
     * @param required whether a missing file is an error. Otherwise it is parsed as an empty map.
     */
    record Single<T>(String name, String file, boolean required, Parser<T> parser)
            implements ContentType<T> {

        @Override
        public List<String> files() {
            return List.of(file);
        }
    }
}
