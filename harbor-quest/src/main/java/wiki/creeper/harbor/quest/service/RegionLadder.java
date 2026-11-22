package wiki.creeper.harbor.quest.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import wiki.creeper.harbor.common.content.type.RegionDef;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.common.id.QuestId;
import wiki.creeper.harbor.common.id.RegionId;
import wiki.creeper.harbor.core.api.HarborPlayer;
import wiki.creeper.harbor.quest.api.QuestState;
import wiki.creeper.harbor.quest.api.RegionProgressProvider;
import wiki.creeper.harbor.quest.content.BoardRules;
import wiki.creeper.harbor.quest.data.QuestData;

/** Highest unlocked region for {@link QuestEngine} (RegionProgressProvider). Main thread only. */
final class RegionLadder {

    private final QuestEngine engine;

    RegionLadder(QuestEngine engine) {
        this.engine = engine;
    }

    /**
     * The World implementation if registered, otherwise the Quest default implementation
     * (regions.yml unlock level + unlock quest).
     */
    Optional<RegionId> highestRegion(UUID player) {
        RegisteredServiceProvider<RegionProgressProvider> reg =
                Bukkit.getServicesManager().getRegistration(RegionProgressProvider.class);
        if (reg != null && !reg.getPlugin().equals(engine.plugin)) {
            try {
                return reg.getProvider().highestUnlockedRegion(player);
            } catch (RuntimeException e) {
                engine.log.warn("RegionProgressProvider 오류: {}", String.valueOf(e));
            }
        }
        return highestUnlockedRegion(player);
    }

    /**
     * Quest default implementation: regions.yml unlock level + unlock quest, in board.yml ladder
     * order.
     */
    Optional<RegionId> highestUnlockedRegion(UUID player) {
        Optional<HarborPlayer> hp = engine.api.players().get(player);
        if (hp.isEmpty()) {
            return Optional.empty();
        }
        Map<HarborId, RegionDef> regions = engine.snapshot().all(RegionDef.TYPE);
        QuestData data = hp.get().get(QuestEngine.COMPONENT);
        RegionId best = null;
        for (BoardRules.RegionGold g : engine.boardRules().ladder()) {
            RegionDef region = regions.get(g.region().id());
            if (region == null) {
                continue;
            }
            RegionDef.Unlock unlock = region.unlock();
            QuestId quest = unlock.quest();
            boolean questDone =
                    quest == null
                            || Optional.ofNullable(data.entries().get(quest))
                                    .map(e -> e.state() == QuestState.COMPLETED)
                                    .orElse(false);
            if (hp.get().level() >= unlock.level() && questDone) {
                best = region.id();
            }
        }
        return Optional.ofNullable(best);
    }
}
