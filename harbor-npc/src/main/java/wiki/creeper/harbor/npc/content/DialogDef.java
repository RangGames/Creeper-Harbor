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

/**
 * 대화 트리 (dialogs/<npc>.yml, 추가 제안, docs 20 5.3). npcs.yml의 dialog가 ID로 가리킨다.
 *
 * <pre>{@code
 * - id: dialog/hansol
 *   entries:                       # 위에서부터 첫 일치 노드를 연다
 *     - {conditions: [quest_available: main], node: offer}
 *     - {node: greet}
 *   nodes:
 *     greet:
 *       pages: [npc.dialog.hansol.greet.1, npc.dialog.hansol.greet.2]   # messages_ko.yml 키
 *       options:
 *         - {label: npc.dialog.option.gift, actions: [gift:open]}
 *         - {label: npc.dialog.option.bye, actions: [close]}
 * }</pre>
 *
 * 검사: entries·goto·노드 인자(node, fail_node, success_node, after_node)가 있는 노드를 가리키는지, entries·external
 * 노드에서 도달할 수 없는 노드가 없는지. 조건·액션 키와 인자는 서버에서 레지스트리로 검사한다.
 */
public record DialogDef(HarborId id, List<Entry> entries, Map<String, Node> nodes) {

    public static final String PREFIX = "dialog/";

    public static final ContentType.Entries<DialogDef> TYPE =
            new ContentType.Entries<>(
                    "dialog", List.of("dialogs/*.yml"), PREFIX, DialogDef::parse, DialogDef::id);

    /** 진입 규칙 한 줄. */
    public record Entry(List<DialogRef> conditions, String node, SourcePos pos) {

        public Entry {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * 대화 노드.
     *
     * @param speaker 화자 이름 덮어쓰기 (없으면 NPC 이름, 이름 변형 반영)
     * @param pages 대사 messages_ko.yml 키. 여러 개면 '다음' 버튼으로 넘긴다
     * @param external 다른 모듈 액션·명령이 직접 여는 노드 (도달 검사 제외, 추가 제안)
     */
    public record Node(
            String id,
            @Nullable String speaker,
            List<String> pages,
            List<Option> options,
            boolean external,
            SourcePos pos) {

        public Node {
            pages = List.copyOf(pages);
            options = List.copyOf(options);
        }
    }

    /**
     * 선택지.
     *
     * @param label 버튼 문구 키
     * @param tooltip 버튼 설명 키 (선택)
     * @param gotoNode 액션이 모두 Continue면 갈 노드 (없으면 닫음)
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

    /** 모든 조건 참조 (진입 규칙 + 선택지). */
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

    /** entries·external 노드에서 시작해 goto·노드 인자로 닿지 않는 노드. */
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
        n.allowOnly(Set.of("speaker", "pages", "page", "options", "external"));
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
        return new Node(
                id,
                n.optString("speaker").orElse(null),
                pages,
                options,
                n.bool("external", false),
                n.pos());
    }

    private static void requireNode(Map<String, Node> nodes, String target, YamlNode at) {
        if (!target.isEmpty() && !nodes.containsKey(target)) {
            at.error("없는 노드를 가리킵니다: '" + target + "'");
        }
    }

    /** goto·노드 인자 존재, 도달 가능성. 오류는 대화 파일 항목 위치에 붙인다. */
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

    /** NPC 기본 키가 가리키는 다른 콘텐츠 (컷씬, NPC) 참조 등록. */
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
