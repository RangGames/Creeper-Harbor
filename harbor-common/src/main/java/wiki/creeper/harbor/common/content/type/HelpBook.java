package wiki.creeper.harbor.common.content.type;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.yaml.YamlNode;

/**
 * help.yml: the player /도움말 (help) list (proposed). Core shows it in this order and hides commands
 * missing on the server (disabled modules) or ones the player lacks permission for. If the file is
 * missing, the list is empty.
 *
 * @param sections categories (in the order written)
 */
public record HelpBook(List<Section> sections) {

    public static final ContentType.Single<HelpBook> TYPE =
            new ContentType.Single<>("help", "help.yml", false, HelpBook::parse);

    /**
     * Command line (without the leading /, words split by spaces). E.g. {@code 낚시 설정}, {@code 기록
     * <어종>}.
     */
    private static final Pattern COMMAND = Pattern.compile("[^\\s/][^\\s]*( [^\\s]+)*");

    public static final HelpBook EMPTY = new HelpBook(List.of());

    public HelpBook {
        sections = List.copyOf(sections);
    }

    /**
     * One category.
     *
     * @param title title (MiniMessage)
     * @param entries command list
     */
    public record Section(String title, List<Entry> entries) {

        public Section {
            entries = List.copyOf(entries);
        }
    }

    /**
     * One command.
     *
     * @param command command inserted on click (without the leading /). Shown only if the first
     *     word is a command registered on the server
     * @param text description (MiniMessage)
     * @param run true runs it immediately on click, false only puts it in the chat box (commands
     *     needing arguments). If empty, false when it contains &lt;
     */
    public record Entry(String command, String text, boolean run) {

        /** Server command name (first word). */
        public String root() {
            int space = command.indexOf(' ');
            return space < 0 ? command : command.substring(0, space);
        }

        /**
         * Command string inserted on click. For input, cut before &lt;argument&gt; and append a
         * space.
         */
        public String clickText() {
            if (run) {
                return "/" + command;
            }
            int arg = command.indexOf(" <");
            return "/" + (arg < 0 ? command : command.substring(0, arg)) + " ";
        }
    }

    static HelpBook parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("sections"));
        List<Section> sections = new ArrayList<>();
        for (YamlNode s : node.list("sections")) {
            s.allowOnly(Set.of("title", "commands"));
            String title = s.string("title", "");
            List<Entry> entries = new ArrayList<>();
            for (YamlNode e : s.list("commands")) {
                e.allowOnly(Set.of("command", "text", "run"));
                String command = e.string("command", "").strip();
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                if (!COMMAND.matcher(command).matches()) {
                    e.error("command는 앞의 / 없이 쓴 명령이어야 합니다: '" + command + "'");
                    continue;
                }
                boolean run = e.bool("run", !command.contains("<"));
                entries.add(new Entry(command.toLowerCase(Locale.ROOT), e.string("text", ""), run));
            }
            if (entries.isEmpty()) {
                s.error("commands가 비어 있습니다");
                continue;
            }
            sections.add(new Section(title, entries));
        }
        return new HelpBook(sections);
    }
}
