package wiki.creeper.harbor.boss.instance;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import wiki.creeper.harbor.boss.content.BossDef;

/** Cell reservation on the instance server (mutable, InstanceManager only). */
final class Reservation {
    final String requestId;
    final BossDef def;
    final int slot;
    final List<UUID> party;
    final UUID leader;
    final String origin;
    final Set<UUID> arrived = new LinkedHashSet<>();
    long deadline;
    boolean confirmed;

    Reservation(
            String requestId,
            BossDef def,
            int slot,
            List<UUID> party,
            UUID leader,
            String origin,
            long deadline) {
        this.requestId = requestId;
        this.def = def;
        this.slot = slot;
        this.party = party;
        this.leader = leader;
        this.origin = origin;
        this.deadline = deadline;
    }
}
