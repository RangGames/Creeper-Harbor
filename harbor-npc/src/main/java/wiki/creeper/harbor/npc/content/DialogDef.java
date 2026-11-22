package wiki.creeper.harbor.npc.content;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.content.ContentType;
import wiki.creeper.harbor.common.content.ParseContext;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.NpcId;
import wiki.creeper.harbor.common.util.SourcePos;
import wiki.creeper.harbor.common.yaml.YamlNode;
import wiki.creeper.harbor.npc.api.DialogStyle;

/**
 * Dialog tree (dialogs/<npc>.yml, proposed, docs 20 5.3). Referenced by ID from dialog in npcs.yml.
 *
 * <pre>{@code
 * - id: dialog/hansol
 *   entries:                       # opens the first matching node from the top
 *     - {conditions: [quest_available: main], node: offer}
 *     - {node: greet}
 *   nodes:
 *     greet:
 *       pages: [npc.dialog.hansol.greet.1, npc.dialog.hansol.greet.2]   # messages_ko.yml keys
 *       style: hologram            # optional: dialog·hologram·title·chat·actionbar (NPC default if absent)
 *       auto: 4                    # optional: next page every 4 seconds in non-DIALOG styles
 *       options:
 *         - {label: npc.dialog.option.gift, actions: [gift:open]}
 *         - {label: npc.dialog.option.bye, actions: [close]}
 * }</pre>
 *
 * Checks: entries, goto and node arguments (node, fail_node, success_node, after_node) point to
 * existing nodes, and no node is unreachable from entries or external nodes. Condition/action keys
 * and arguments are checked on the server against the registry.
 */
public record DialogDef(HarborId id, List<Entry> entries, Map<String, Node> nodes) {

    public static final String PREFIX = "dialog/";

    public static final ContentType.Entries<DialogDef> TYPE =
            new ContentType.Entries<>(
                    "dialog", List.of("dialogs/*.yml"), PREFIX, DialogDef::parse, DialogDef::id);

    /** One entry rule. */
    public record Entry(List<DialogRef> conditions, String node, SourcePos pos) {

        public Entry {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * Dialog node.
     *
     * @param speaker speaker name override (otherwise the NPC name, with name variants)
     * @param pages messages_ko.yml keys of the lines. With several, the 'next' button advances
     * @param external node opened directly by other modules' actions or commands (excluded from the
     *     reachability check, proposed)
     * @param style presentation style (NPC default if absent, proposed)
     * @param autoSeconds in non-DIALOG styles, advance to the next page after this many seconds (0
     *     means never, proposed)
     */
    public record Node(
            String id,
            @Nullable String speaker,
            List<String> pages,
            List<Option> options,
            boolean external,
            @Nullable DialogStyle style,
            double autoSeconds,
            SourcePos pos) {

        public Node {
            pages = List.copyOf(pages);
            options = List.copyOf(options);
        }

        public Node(
                String id,
                @Nullable String speaker,
                List<String> pages,
                List<Option> options,
                boolean external,
                SourcePos pos) {
            this(id, speaker, pages, options, external, null, 0, pos);
        }
    }

    /**
     * Choice.
     *
     * @param label button text key
     * @param tooltip button description key (optional)
     * @param gotoNode node to go to if all actions are Continue (closes if absent)
     */
    public record Option(
            String label,
            @Nullable String tooltip,
            List<DialogRef> conditions,
            List<DialogRef> actions,
            @Nullable String gotoNode,
            SourcePos pos) {

        public Option {
            conditions = List.copyOf(conditions);
            actions = List.copyOf(actions);
        }
    }

    public DialogDef {
        entries = List.copyOf(entries);
        nodes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
    }

    /** All condition references (entry rules + choices). */
    public List<DialogRef> allConditions() {
        List<DialogRef> all = new ArrayList<>();
        entries.forEach(e -> all.addAll(e.conditions()));
        nodes.values().forEach(n -> n.options().forEach(o -> all.addAll(o.conditions())));
        return all;
    }

    public List<DialogRef> allActions() {
        List<DialogRef> all = new ArrayList<>();
        nodes.values().forEach(n -> n.options().forEach(o -> all.addAll(o.actions())));
        return all;
    }

    /** Nodes not reachable via goto or node arguments starting from entries and external nodes. */
    public Set<String> unreachable() {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        entries.forEach(e -> queue.add(e.node()));
        nodes.values().stream().filter(Node::external).forEach(n -> queue.add(n.id()));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!seen.add(id)) {
                continue;
            }
            Node node = nodes.get(id);
            if (node == null) {
                continue;
            }
            for (Option option : node.options()) {
                if (option.gotoNode() != null) {
                    queue.add(option.gotoNode());
                }
                option.actions().forEach(a -> queue.addAll(a.nodeRefs()));
            }
        }
        Set<String> unreachable = new java.util.TreeSet<>(nodes.keySet());
        unreachable.removeAll(seen);
        return unreachable;
    }

    static DialogDef parse(YamlNode node, ParseContext ctx) {
        node.allowOnly(Set.of("id", "entries", "nodes"));
        HarborId id =
                node.require("id")
                        .flatMap(n -> n.asId(PREFIX))
                        .orElse(HarborId.parse("dialog/invalid"));
        Map<String, Node> nodes = new LinkedHashMap<>();
        node.require("nodes")
                .ifPresent(
                        map ->
                                map.entries()
                                        .forEach(
                                                (nodeId, n) -> {
                                                    if (!NODE_ID.matcher(nodeId).matches()) {
                                                        n.error(
                                                                "노드 ID는 소문자·숫자·밑줄이어야 합니다: '"
                                                                        + nodeId
                                                                        + "'");
                                                        return;
                                                    }
                                                    nodes.put(nodeId, parseNode(nodeId, n));
                                                }));
        List<Entry> entries = new ArrayList<>();
        Optional<YamlNode> entriesNode = node.require("entries");
        for (YamlNode e : entriesNode.map(YamlNode::asList).orElse(List.of())) {
            e.allowOnly(Set.of("conditions", "node"));
            List<DialogRef> conditions =
                    e.get("conditions").map(DialogRef::parseList).orElse(List.of());
            String target = e.string("node");
            entries.add(new Entry(conditions, target, e.pos()));
            requireNode(nodes, target, e);
        }
        if (entriesNode.isPresent() && entries.isEmpty()) {
            entriesNode.get().error("entries가 비어 있습니다 (마지막 줄은 조건 없는 기본 노드로 두세요)");
        }
        DialogDef def = new DialogDef(id, entries, nodes);
        validateLinks(def, node);
        registerRefs(def, ctx);
        return def;
    }

    private static final java.util.regex.Pattern NODE_ID =
            java.util.regex.Pattern.compile("[a-z0-9_]+");

    private static Node parseNode(String id, YamlNode n) {
        n.allowOnly(Set.of("speaker", "pages", "page", "options", "external", "style", "auto"));
        List<String> pages = new ArrayList<>();
        n.get("page").ifPresent(p -> pages.add(p.asString()));
        pages.addAll(n.strings("pages"));
        List<Option> options = new ArrayList<>();
        for (YamlNode o : n.list("options")) {
            o.allowOnly(Set.of("label", "tooltip", "conditions", "actions", "goto"));
            options.add(
                    new Option(
                            o.string("label"),
                            o.optString("tooltip").orElse(null),
                            o.get("conditions").map(DialogRef::parseList).orElse(List.of()),
                            o.get("actions").map(DialogRef::parseList).orElse(List.of()),
                            o.optString("goto").orElse(null),
                            o.pos()));
        }
        double auto = n.number("auto", 0);
        if (auto < 0) {
            n.get("auto").ifPresent(a -> a.error("auto는 0 이상(초)이어야 합니다"));
            auto = 0;
        }
        return new Node(
                id,
                n.optString("speaker").orElse(null),
                pages,
                options,
                n.bool("external", false),
                parseStyle(n, "style"),
                auto,
                n.pos());
    }

    /**
     * A style field such as {@code style:}. On an unknown value, records an error with its position
     * and returns null.
     */
    public static @Nullable DialogStyle parseStyle(YamlNode parent, String key) {
        Optional<YamlNode> raw = parent.get(key);
        if (raw.isEmpty()) {
            return null;
        }
        String value = raw.get().asString();
        Optional<DialogStyle> style = DialogStyle.parse(value);
        if (style.isEmpty()) {
            raw.get().error("모르는 대화 방식입니다: '" + value + "' (가능: " + DialogStyle.keys() + ")");
            return null;
        }
        return style.get();
    }

    private static void requireNode(Map<String, Node> nodes, String target, YamlNode at) {
        if (!target.isEmpty() && !nodes.containsKey(target)) {
            at.error("없는 노드를 가리킵니다: '" + target + "'");
        }
    }

    /**
     * goto/node argument existence and reachability. Errors are attached to the dialog file entry
     * position.
     */
    private static void validateLinks(DialogDef def, YamlNode at) {
        for (Node n : def.nodes().values()) {
            for (Option option : n.options()) {
                String target = option.gotoNode();
                if (target != null && !def.nodes().containsKey(target)) {
                    at.errors().add(option.pos(), "goto가 없는 노드를 가리킵니다: '" + target + "'");
                }
                for (DialogRef action : option.actions()) {
                    for (String ref : action.nodeRefs()) {
                        if (!def.nodes().containsKey(ref)) {
                            at.errors()
                                    .add(
                                            action.pos(),
                                            "액션 "
                                                    + action.key()
                                                    + "이(가) 없는 노드를 가리킵니다: '"
                                                    + ref
                                                    + "'");
                        }
                    }
                }
            }
        }
        for (String id : def.unreachable()) {
            Node n = def.nodes().get(id);
            at.errors()
                    .add(
                            n == null ? at.pos() : n.pos(),
                            "도달할 수 없는 노드입니다: '" + id + "' (다른 모듈이 직접 연다면 external: true)");
        }
    }

    /** Registers references to other content (cutscenes, NPCs) pointed to by NPC built-in keys. */
    private static void registerRefs(DialogDef def, ParseContext ctx) {
        List<DialogRef> refs = new ArrayList<>(def.allActions());
        refs.addAll(def.allConditions());
        for (DialogRef ref : refs) {
            if (ref.key().equals("cutscene:play")) {
                ref.args()
                        .string("id")
                        .or(() -> ref.args().value())
                        .flatMap(HarborId::tryParse)
                        .ifPresent(id -> ctx.ref("cutscene", id, ref.pos()));
            }
            ref.args()
                    .string("npc")
                    .flatMap(HarborId::tryParse)
                    .filter(id -> id.hasPrefix(NpcId.PREFIX))
                    .ifPresent(id -> ctx.ref("npc", id, ref.pos()));
        }
    }
}
