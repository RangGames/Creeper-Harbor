package wiki.creeper.harbor.core.player;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.model.JobLine;
import wiki.creeper.harbor.common.model.JobTier;
import wiki.creeper.harbor.common.model.PlayerRef;
import wiki.creeper.harbor.common.model.SeasonId;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.core.api.PlayerComponent;

/**
 * HarborPlayer implementation. Modified on the main thread only. Saves hand over a copy made by
 * {@link #snapshotBase()}.
 */
final class PlayerState implements HarborPlayer {

    /**
     * Copy of the base fields for saving. Balances are managed by the ledger, so they are not
     * saved.
     */
    record BaseSnapshot(
            UUID uuid,
            String name,
            SeasonId season,
            long playtimeSeconds,
            @Nullable JobLine jobLine,
            JobTier jobTier,
            int level,
            long exp,
            int reputation) {}

    private final UUID uuid;
    private final String name;
    private final Instant firstJoin;
    private final SeasonId season;
    private final long storedPlaytimeSeconds;
    private final Map<Currency, Long> balances = new EnumMap<>(Currency.class);
    private final Map<String, Object> components = new ConcurrentHashMap<>();
    private final Set<String> dirtyComponents = new HashSet<>();
    private Instant sessionStart = Instant.now();
    private @Nullable JobLine jobLine;
    private JobTier jobTier;
    private int level;
    private long exp;
    private int reputation;
    private boolean baseDirty;
    private volatile @Nullable String handoffRequest;
    private volatile boolean handoffSaved;
    private volatile boolean handoffLocked;
    private volatile boolean abortRequested;
    private volatile boolean left;

    PlayerState(PlayerRepository.Row row, Map<String, Object> loadedComponents) {
        this.uuid = row.uuid();
        this.name = row.name();
        this.firstJoin = row.firstJoin();
        this.season = row.season();
        this.storedPlaytimeSeconds = row.playtimeSeconds();
        this.jobLine = row.jobLine();
        this.jobTier = row.jobTier();
        this.level = row.level();
        this.exp = row.exp();
        this.reputation = row.reputation();
        this.balances.put(Currency.GOLD, row.gold());
        this.balances.put(Currency.MULBINEUL, row.mulbineul());
        this.components.putAll(loadedComponents);
    }

    void markJoined() {
        sessionStart = Instant.now();
    }

    /**
     * The request ID if a server move was requested. While only requested (waiting on a handoff
     * blocker delay) nothing is saved yet; from {@link #handoffSaved()} on, the save is done (or
     * enqueued).
     */
    @Nullable String handoffRequest() {
        return handoffRequest;
    }

    boolean handingOff() {
        return handoffRequest != null;
    }

    /**
     * Clearing the request also clears the save-stage marks (keep playing after a cancel or
     * failure).
     */
    void setHandoffRequest(@Nullable String requestId) {
        handoffRequest = requestId;
        if (requestId == null) {
            handoffSaved = false;
            handoffLocked = false;
            abortRequested = false;
        }
    }

    /**
     * The lock was handed off (beginHandoff succeeded). An abort arriving before this is deferred
     * via {@link #requestAbort}.
     */
    boolean handoffLocked() {
        return handoffLocked;
    }

    void markHandoffLocked() {
        handoffLocked = true;
    }

    boolean abortRequested() {
        return abortRequested;
    }

    void requestAbort() {
        abortRequested = true;
    }

    /**
     * The move snapshot was taken. From here on nothing is saved and player actions are blocked
     * (like HuskSync's locked state: changes after the snapshot are kept nowhere, so dropping items
     * duplicates them).
     */
    boolean handoffSaved() {
        return handoffSaved;
    }

    void markHandoffSaved() {
        handoffSaved = true;
    }

    /** Quit this server (for lock cleanup when async handoff handling finishes late). */
    boolean left() {
        return left;
    }

    void markLeft() {
        left = true;
    }

    void setCachedBalance(Currency currency, long balance) {
        balances.put(currency, balance);
    }

    @Override
    public UUID uuid() {
        return uuid;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public PlayerRef ref() {
        return new PlayerRef(uuid, name);
    }

    @Override
    public Instant firstJoin() {
        return firstJoin;
    }

    @Override
    public Duration playtime() {
        return Duration.ofSeconds(storedPlaytimeSeconds)
                .plus(Duration.between(sessionStart, Instant.now()));
    }

    @Override
    public SeasonId season() {
        return season;
    }

    @Override
    public long balance(Currency currency) {
        return balances.getOrDefault(currency, 0L);
    }

    @Override
    public int reputation() {
        return reputation;
    }

    @Override
    public void setReputation(int reputation) {
        this.reputation = reputation;
        baseDirty = true;
    }

    @Override
    public Optional<JobLine> jobLine() {
        return Optional.ofNullable(jobLine);
    }

    @Override
    public JobTier jobTier() {
        return jobTier;
    }

    @Override
    public int level() {
        return level;
    }

    @Override
    public long exp() {
        return exp;
    }

    @Override
    public void setJob(@Nullable JobLine line, JobTier tier, int level, long exp) {
        this.jobLine = line;
        this.jobTier = tier;
        this.level = level;
        this.exp = exp;
        baseDirty = true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(PlayerComponent<T> component) {
        Object value = components.get(component.id());
        if (value == null) {
            throw new IllegalArgumentException("등록되지 않은 플레이어 컴포넌트: " + component.id());
        }
        return (T) value;
    }

    @Override
    public <T> void set(PlayerComponent<T> component, T value) {
        if (!components.containsKey(component.id())) {
            throw new IllegalArgumentException("등록되지 않은 플레이어 컴포넌트: " + component.id());
        }
        components.put(component.id(), value);
        dirtyComponents.add(component.id());
    }

    BaseSnapshot snapshotBase() {
        return new BaseSnapshot(
                uuid,
                name,
                season,
                playtime().toSeconds(),
                jobLine,
                jobTier,
                level,
                exp,
                reputation);
    }

    /** Because of play time, the base fields always have something to save. */
    boolean baseDirty() {
        return baseDirty;
    }

    void clearBaseDirty() {
        baseDirty = false;
    }

    /** Takes the changed component IDs and clears the marks. All of them if all is set. */
    Set<String> drainDirtyComponents(boolean all) {
        Set<String> result =
                all ? new HashSet<>(components.keySet()) : new HashSet<>(dirtyComponents);
        dirtyComponents.clear();
        return result;
    }

    @Nullable Object component(String id) {
        return components.get(id);
    }
}
