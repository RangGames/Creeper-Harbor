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
 * 조우 종료 (docs 20 10.2). 결과(CLEARED·FAILED·ABORTED)와 참가자별 기여도·순위. Quest 보스 처치 목표, Collection 도감(보스
 * 어종 · release_only 어종 등록), Shop 시즌 패스 경험치가 구독한다. 보상 지급 전에 메인 스레드에서 발생한다.
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

    /** 보스 ID (Quest 보스 처치 목표가 읽는다). */
    public wiki.creeper.harbor.common.id.BossId boss() {
        return encounter.boss();
    }

    /** 참가자 UUID (파티장 먼저). 월드 보스는 기여한 사람 전원. */
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

    /** 순위순. */
    public List<Contribution> contributions() {
        return contributions;
    }

    public Optional<Contribution> contribution(UUID player) {
        return contributions.stream().filter(c -> c.player().equals(player)).findFirst();
    }

    /** 연결된 보스 어종 (없으면 빈 값). */
    public Optional<FishId> fish() {
        return Optional.ofNullable(fish);
    }

    /** 보스 어종이 release_only라 아이템 없이 도감에만 올리는지 (해구의 군주·녹빛 폭뢰어). */
    public boolean releaseOnly() {
        return releaseOnly;
    }

    public long durationMs() {
        return durationMs;
    }

    /** 스토리 전투 (전리품·기록 없음). */
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
