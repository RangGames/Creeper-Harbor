package wiki.creeper.harbor.proxy;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
        Login login,
        Alt alt,
        SanctionDefaults sanctions,
        int joinLogRetentionDays,
        int heartbeatAlertSeconds,
        List<Link> links,
        Map<String, String> messages) {

    /**
     * Queue settings.
     *
     * @param autoJoin whether to queue for the main server right after the resource pack is applied
     *     in the lobby (false means departure via /출항, NPC or GUI, proposed)
     */
    record Queue(
            int normalShare,
            int graceSeconds,
            int perSecond,
            double tpsFreeze,
            double tpsResume,
            boolean autoJoin) {}

    /** Chat spam protection settings. */
    record Chat(int repeatLimit, int linesPerSecond, int blockSeconds, List<String> bannedWords) {}

    /** Login check time limit (docs 20 1.4 step 2: defaults if not done within 1 second). */
    record Login(int lookupTimeoutMs, int sanctionTimeoutMs) {}

    /** Multi-account detection (proposed values: 30 days, 3 accounts). */
    record Alt(int windowDays, int alertAccounts) {}

    /** Default duration for sanctions given without a duration. */
    record SanctionDefaults(SanctionTerm ban, SanctionTerm mute) {}

    /**
     * Pause-menu server link ('8. System details' chapter 11). With type, the vanilla default name;
     * otherwise label (MiniMessage).
     *
     * @param type ServerLink.Type name (WEBSITE, COMMUNITY, BUG_REPORT …)
     */
    record Link(@Nullable String type, String label, URI url) {}

    /** Text of the default config in the jar (fills keys missing from the server config). */
    static Map<String, String> defaultMessages(InputStream in) {
        ValidationErrors errors = new ValidationErrors();
        Map<String, String> messages = new LinkedHashMap<>();
        YamlNode.parse(
                        "harbor-proxy.yml (기본값)",
                        new InputStreamReader(in, StandardCharsets.UTF_8),
                        errors)
                .flatMap(root -> root.get("messages"))
                .ifPresent(n -> flatten("", n, messages));
        return Map.copyOf(messages);
    }

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
                        q.number("tps_resume", 19.5),
                        q.bool("auto_join", true));

        YamlNode m = root.get("maintenance").orElse(root);
        YamlNode c = root.get("chat").orElse(root);
        Chat chat =
                new Chat(
                        c.integer("repeat_limit", 3, 1, 100),
                        c.integer("lines_per_second", 3, 1, 100),
                        c.integer("block_seconds", 3, 0, 3600),
                        c.strings("banned_words"));

        YamlNode l = root.get("login").orElse(root);
        Login login =
                new Login(
                        l.integer("lookup_timeout_ms", 1000, 50, 10_000),
                        l.integer("sanction_timeout_ms", 3000, 100, 30_000));
        YamlNode a = root.get("alt").orElse(root);
        Alt alt =
                new Alt(
                        a.integer("window_days", 30, 1, 365),
                        a.integer("alert_accounts", 3, 2, 100));
        YamlNode sn = root.get("sanction").orElse(root);
        SanctionDefaults sanctions =
                new SanctionDefaults(
                        term(sn, "default_ban", "perm"), term(sn, "default_mute", "1h"));

        List<Link> links = new ArrayList<>();
        for (YamlNode n : root.list("links")) {
            String url = n.string("url");
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                n.error("링크 URL 형식이 아닙니다: " + url);
                continue;
            }
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
                n.error("링크는 http(s)만 됩니다: " + url);
                continue;
            }
            String type = n.optString("type").map(t -> t.toUpperCase(Locale.ROOT)).orElse(null);
            String label = n.string("label", "");
            if (type == null && label.isEmpty()) {
                n.error("링크에는 type 또는 label이 있어야 합니다");
                continue;
            }
            links.add(new Link(type, label, uri));
        }

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
                login,
                alt,
                sanctions,
                root.get("join_log").orElse(root).integer("retention_days", 90, 1, 3650),
                root.get("heartbeat").orElse(root).integer("alert_seconds", 60, 15, 3600),
                List.copyOf(links),
                Map.copyOf(messages));
    }

    private static SanctionTerm term(YamlNode node, String key, String fallback) {
        String raw = node.string(key, fallback);
        Optional<SanctionTerm> term = SanctionTerm.parse(raw);
        if (term.isEmpty()) {
            node.error("'" + key + "' 기간 형식이 잘못되었습니다 (예: 30m, 12h, 7d, perm): " + raw);
            return SanctionTerm.PERMANENT;
        }
        return term.get();
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
