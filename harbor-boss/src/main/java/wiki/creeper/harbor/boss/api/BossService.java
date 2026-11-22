package wiki.creeper.harbor.boss.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.common.id.BossId;

/**
 * Boss definition and encounter lookup, plus admin forced start/end (docs 20 10.2). Main thread
 * only (definition lookup works anywhere).
 *
 * <pre>{@code
 * BossService boss = Bukkit.getServicesManager().load(BossService.class);
 * if (boss != null && boss.inEncounter(uuid)) { ... }   // block shops and cutscenes during boss fights
 * }</pre>
 */
public interface BossService {

    /** Start result. */
    enum StartResult {
        STARTED,
        UNKNOWN_BOSS,
        /** Party size is outside min-max. */
        PARTY_SIZE,
        /** A party member is outside the arena radius. */
        OUT_OF_RANGE,
        /** No free arena slot (or concurrent encounter cap reached). */
        NO_SLOT,
        /** A party member is already in an encounter. */
        BUSY,
        /** Summon cooldown for the same party leader. */
        COOLDOWN,
        /** BossEncounterStartEvent was cancelled (maintenance, etc.). */
        CANCELLED,
        /** This boss cannot be started on this server (instance or world arena). */
        WRONG_SERVER,
        /** Not the party leader. */
        NOT_LEADER
    }

    Collection<BossDef> definitions();

    Optional<BossDef> definition(BossId boss);

    /** Encounter the player is in (including one whose slot is held after they left). */
    Optional<EncounterInfo> encounterOf(UUID player);

    boolean inEncounter(UUID player);

    List<EncounterInfo> encounters();

    /**
     * Admin forced start (without a summon bait). Moves the leader's whole party to the free slot
     * nearest the leader and starts (radius check skipped).
     */
    StartResult forceStart(BossId boss, Player leader);

    /**
     * Admin forced end.
     *
     * @param result CLEARED · FAILED · ABORTED
     */
    boolean forceEnd(UUID encounterId, EncounterState result);

    /**
     * This week's reward count for one boss (the weekly limit from balance.yml {@code
     * boss.types.<type>.weekly}, counted on clears as {@code boss:<name>} in the Season weekly
     * counter). Clears beyond the limit still count as clears but give reduced rewards.
     *
     * @param used clears rewarded this week
     * @param remaining rewards left this week (0 or more)
     * @param limit weekly limit
     */
    record WeeklyRewards(int used, int remaining, int limit) {}

    /**
     * Reads a player's weekly reward count for a boss (DB, asynchronous; completes on the main
     * thread). Empty for an unknown boss or a story boss (no weekly count).
     */
    CompletableFuture<Optional<WeeklyRewards>> weeklyRewards(UUID player, BossId boss);
}
