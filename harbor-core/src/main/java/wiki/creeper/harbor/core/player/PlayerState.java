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

/** HarborPlayer 구현. 메인 스레드에서만 고친다. 저장할 때는 {@link #snapshotBase()}로 복사본을 만들어 넘긴다. */
final class PlayerState implements HarborPlayer {

    /** 저장용 기본 필드 복사본. 잔액은 원장이 관리하므로 저장하지 않는다. */
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

    /** 서버 이동 중이면 그 요청 ID. 이동 중에는 저장하지 않는다 (이미 저장하고 잠금을 넘겼다). */
    @Nullable String handoffRequest() {
        return handoffRequest;
    }

    boolean handingOff() {
        return handoffRequest != null;
    }

    void setHandoffRequest(@Nullable String requestId) {
        handoffRequest = requestId;
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

    /** 플레이 시간 때문에 기본 필드는 항상 저장할 거리가 있다. */
    boolean baseDirty() {
        return baseDirty;
    }

    void clearBaseDirty() {
        baseDirty = false;
    }

    /** 바뀐 컴포넌트 ID를 꺼내고 표시를 지운다. all이면 전부. */
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
