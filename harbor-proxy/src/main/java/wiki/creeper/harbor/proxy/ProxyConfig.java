package wiki.creeper.harbor.proxy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.common.yaml.YamlNode;

/** harbor-proxy.yml (docs 20 1.3). */
record ProxyConfig(
        String lobby,
        Map<String, Integer> mainServers,
        Map<String, List<String>> instanceServers,
        Queue queue,
        Set<UUID> priority,
        Set<UUID> maintenanceBypass,
        boolean maintenanceFallback,
        String ipHashSecret,
        Chat chat,
        Map<String, String> messages) {

    /** 대기열 설정. */
    record Queue(
            int normalShare, int graceSeconds, int perSecond, double tpsFreeze, double tpsResume) {}

    /** 채팅 도배 방지 설정. */
    record Chat(int repeatLimit, int linesPerSecond, int blockSeconds, List<String> bannedWords) {}

    static Result<ProxyConfig, ValidationErrors> load(Path file) {
        ValidationErrors errors = new ValidationErrors();
        Optional<YamlNode> root = YamlNode.read(file, file.getFileName().toString(), errors);
        if (root.isEmpty()) {
            return Result.err(errors);
        }
        ProxyConfig config = parse(root.get());
        return errors.isEmpty() ? Result.ok(config) : Result.err(errors);
    }

    private static ProxyConfig parse(YamlNode root) {
        Map<String, Integer> mains = new LinkedHashMap<>();
        root.get("main_servers")
                .ifPresent(n -> n.entries().forEach((k, v) -> mains.put(k, v.asInt())));
        if (mains.isEmpty()) {
            root.error("main_servers에 본 서버가 하나 이상 있어야 합니다");
        }
        Map<String, List<String>> instances = new LinkedHashMap<>();
        root.get("instance_servers")
                .ifPresent(
                        n ->
                                n.entries()
                                        .forEach(
                                                (k, v) ->
                                                        instances.put(
                                                                k,
                                                                v.asList().stream()
                                                                        .map(YamlNode::asString)
                                                                        .toList())));

        YamlNode q = root.get("queue").orElse(root);
        Queue queue =
                new Queue(
                        q.integer("normal_share", 4, 1, 100),
                        q.integer("grace_seconds", 180, 0, 3600),
                        q.integer("per_second", 3, 1, 100),
                        q.number("tps_freeze", 18.0),
                        q.number("tps_resume", 19.5));

        YamlNode m = root.get("maintenance").orElse(root);
        YamlNode c = root.get("chat").orElse(root);
        Chat chat =
                new Chat(
                        c.integer("repeat_limit", 3, 1, 100),
                        c.integer("lines_per_second", 3, 1, 100),
                        c.integer("block_seconds", 3, 0, 3600),
                        c.strings("banned_words"));

        Map<String, String> messages = new LinkedHashMap<>();
        root.get("messages").ifPresent(n -> flatten("", n, messages));

        String secret = root.string("ip_hash_secret");
        return new ProxyConfig(
                root.string("lobby", "lobby"),
                Map.copyOf(mains),
                Map.copyOf(instances),
                queue,
                uuids(root.list("priority")),
                uuids(m.list("bypass")),
                m.bool("fallback", false),
                secret,
                chat,
                Map.copyOf(messages));
    }

    private static Set<UUID> uuids(List<YamlNode> nodes) {
        List<UUID> result = new ArrayList<>();
        for (YamlNode n : nodes) {
            try {
                result.add(UUID.fromString(n.asString()));
            } catch (IllegalArgumentException e) {
                n.error("UUID 형식이 아닙니다: " + n.asString());
            }
        }
        return Set.copyOf(result);
    }

    private static void flatten(String prefix, YamlNode node, Map<String, String> out) {
        if (node.isMap()) {
            node.entries()
                    .forEach((k, v) -> flatten(prefix.isEmpty() ? k : prefix + "." + k, v, out));
        } else {
            out.put(prefix, node.asString());
        }
    }
}
