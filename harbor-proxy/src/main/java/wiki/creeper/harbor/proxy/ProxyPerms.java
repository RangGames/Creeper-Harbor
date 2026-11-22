package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.command.CommandSource;
import java.util.List;

/** Proxy permissions (docs 20 1.5, 0.4: harbor.admin.proxy.&lt;command&gt;). */
final class ProxyPerms {

    static final String MAINTENANCE = "harbor.admin.proxy.maintenance";
    static final String SEND = "harbor.admin.proxy.send";
    static final String QUEUE = "harbor.admin.proxy.queue";
    static final String QUEUE_BYPASS = "harbor.admin.proxy.queue.bypass";
    static final String SERVERS = "harbor.admin.proxy.servers";
    static final String LOOKUP = "harbor.admin.proxy.lookup";
    static final String SANCTION = "harbor.admin.proxy.sanction";
    static final String ANNOUNCE = "harbor.admin.proxy.announce";

    /** Whether to show the /hp root: if any subcommand permission is held. */
    private static final List<String> ADMIN =
            List.of(MAINTENANCE, SEND, QUEUE, SERVERS, LOOKUP, SANCTION, ANNOUNCE);

    private ProxyPerms() {}

    static boolean anyAdmin(CommandSource source) {
        return ADMIN.stream().anyMatch(source::hasPermission);
    }
}
