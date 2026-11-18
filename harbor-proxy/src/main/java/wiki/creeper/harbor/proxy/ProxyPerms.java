package wiki.creeper.harbor.proxy;

import com.velocitypowered.api.command.CommandSource;
import java.util.List;

/** 프록시 권한 (docs 20 1.5, 0.4: harbor.admin.proxy.&lt;명령&gt;). */
final class ProxyPerms {

    static final String MAINTENANCE = "harbor.admin.proxy.maintenance";
    static final String SEND = "harbor.admin.proxy.send";
    static final String QUEUE = "harbor.admin.proxy.queue";
    static final String QUEUE_BYPASS = "harbor.admin.proxy.queue.bypass";
    static final String SERVERS = "harbor.admin.proxy.servers";
    static final String LOOKUP = "harbor.admin.proxy.lookup";
    static final String SANCTION = "harbor.admin.proxy.sanction";
    static final String ANNOUNCE = "harbor.admin.proxy.announce";

    /** /hp 루트를 보여 줄지: 하위 명령 권한 중 하나라도 있으면. */
    private static final List<String> ADMIN =
            List.of(MAINTENANCE, SEND, QUEUE, SERVERS, LOOKUP, SANCTION, ANNOUNCE);

    private ProxyPerms() {}

    static boolean anyAdmin(CommandSource source) {
        return ADMIN.stream().anyMatch(source::hasPermission);
    }
}
