package wiki.creeper.harbor.boss.api.event;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.boss.api.Contribution;
import wiki.creeper.harbor.boss.api.EncounterInfo;
import wiki.creeper.harbor.boss.api.EncounterState;
import wiki.creeper.harbor.boss.content.BossType;
import wiki.creeper.harbor.common.id.FishId;

/**
 * Encounter end (docs 20 10.2). Result (CLEARED, FAILED, ABORTED) plus per-participant contribution
 * and rank. Subscribed by Quest boss kill objectives, Collection (registering boss fish and
 * release_only fish), and Shop season pass experience. Fired on the main thread before rewards are
 * granted.
 */
public final class BossEncounterEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final EncounterInfo encounter;
    private final EncounterState result;
    private final BossType type;
    private final List<Contribution> contributions;
    private final @Nullable FishId fish;
    private final boolean releaseOnly;
    private final long durationMs;
    private final boolean story;

    public BossEncounterEndEvent(
            EncounterInfo encounter,
            EncounterState result,
            BossType type,
            List<Contribution> contributions,
            @Nullable FishId fish,
            boolean releaseOnly,
            long durationMs,
            boolean story) {
        this.encounter = encounter;
        this.result = result;
        this.type = type;
        this.contributions = List.copyOf(contributions);
        this.fish = fish;
        this.releaseOnly = releaseOnly;
        this.durationMs = durationMs;
        this.story = story;
    }

    public EncounterInfo encounter() {
        return encounter;
    }

    /** Boss ID (read by Quest boss kill objectives). */
    public wiki.creeper.harbor.common.id.BossId boss() {
        return encounter.boss();
    }

    /** Participant UUIDs (party leader first). For world bosses, everyone who contributed. */
    public List<UUID> participants() {
        return encounter.members();
    }

    public EncounterState result() {
        return result;
    }

    public boolean cleared() {
        return result == EncounterState.CLEARED;
    }

    public BossType type() {
        return type;
    }

    /** In rank order. */
    public List<Contribution> contributions() {
        return contributions;
    }

    public Optional<Contribution> contribution(UUID player) {
        return contributions.stream().filter(c -> c.player().equals(player)).findFirst();
    }

    /** Linked boss fish (empty if none). */
    public Optional<FishId> fish() {
        return Optional.ofNullable(fish);
    }

    /**
     * Whether the boss fish is release_only, so it is only registered in the collection without an
     * item (Lord of the Trench, Verdigris Thunderfish).
     */
    public boolean releaseOnly() {
        return releaseOnly;
    }

    public long durationMs() {
        return durationMs;
    }

    /** Story fight (no loot or records). */
    public boolean story() {
        return story;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
