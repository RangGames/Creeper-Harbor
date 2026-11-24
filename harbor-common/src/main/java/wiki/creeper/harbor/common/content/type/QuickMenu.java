package wiki.creeper.harbor.common.content.type;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * quick_menu.yml: the quick actions menu opened with the client quick actions key (default G, "4.
 * Fishing presentation" 1.3, proposed). Core registers it as a Dialog in the bootstrap stage and
 * adds it to the {@code minecraft:quick_actions} tag, so no datapack is needed. Because registry
 * entries are fixed at startup, changes apply after a restart (validated on reload).
 *
 * @param title dialog title (MiniMessage)
 * @param columns buttons per row
 * @param buttons buttons in order
 */
public record QuickMenu(String title, int columns, List<Button> buttons) {

    public static final ContentType.Single<QuickMenu> TYPE =
            new ContentType.Single<>("quick_menu", "quick_menu.yml", false, QuickMenu::parse);

    public static final String FILE = "quick_menu.yml";

    private static final Pattern COMMAND = Pattern.compile("[^\\s/][^\\s]*( [^\\s]+)*");

    public QuickMenu {
        buttons = List.copyOf(buttons);
    }

    /**
     * One button.
     *
     * @param label label (MiniMessage)
     * @param tooltip tooltip (MiniMessage, empty for none)
     * @param command command run as the player (without the leading /)
     * @param width button width (1~1024)
     */
    public record Button(String label, String tooltip, String command, int width) {}

    static QuickMenu parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("title", "columns", "buttons"));
        List<Button> buttons = new ArrayList<>();
        for (YamlNode b : node.list("buttons")) {
            b.allowOnly(Set.of("label", "tooltip", "command", "width"));
            String command = b.string("command");
            if (!COMMAND.matcher(command).matches()) {
                b.error("command는 / 없이 쓴 명령어여야 합니다: " + command);
            }
            buttons.add(
                    new Button(
                            b.string("label"),
                            b.string("tooltip", ""),
                            command,
                            b.integer("width", 150, 1, 1024)));
        }
        // Without the file (or buttons) the menu is simply not registered
        return new QuickMenu(node.string("title", ""), node.integer("columns", 2, 1, 8), buttons);
    }

    /**
     * Reads the menu outside the content loader (Core bootstrap, before content sync): the server's
     * file if present and valid, otherwise the bundled default.
     *
     * @param serverFile plugins/Harbor/content/quick_menu.yml
     * @param bundled the bundled default (null if absent)
     */
    public static Optional<QuickMenu> load(Path serverFile, @Nullable InputStream bundled)
            throws IOException {
        if (Files.isRegularFile(serverFile)) {
            try (Reader reader = Files.newBufferedReader(serverFile, StandardCharsets.UTF_8)) {
                Optional<QuickMenu> menu = read(reader);
                if (menu.isPresent()) {
                    return menu;
                }
            }
        }
        if (bundled == null) {
            return Optional.empty();
        }
        try (Reader reader = new InputStreamReader(bundled, StandardCharsets.UTF_8)) {
            return read(reader);
        }
    }

    private static Optional<QuickMenu> read(Reader reader) {
        ValidationErrors errors = new ValidationErrors();
        Optional<QuickMenu> menu =
                YamlNode.parse(FILE, reader, errors).map(n -> parse(n, new ParseContext()));
        return errors.isEmpty() ? menu : Optional.empty();
    }
}
