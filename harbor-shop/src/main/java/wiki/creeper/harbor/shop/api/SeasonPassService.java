package wiki.creeper.harbor.shop.api;

import java.util.Set;
import java.util.UUID;
import org.bukkit.plugin.Plugin;

/**
 * Season pass "Voyage Log" (docs 20 12.2·12.4). Exp only rises through normal play. Handles online
 * players only; main thread only.
 *
 * <p><b>Connecting exp sources (PassExpSource registration API).</b> The amount is
 * exp_sources[source key] in shop/pass_s&lt;n&gt;.yml × units (source modules do not know the
 * numbers). Harbor-Quest (QuestCompletedEvent: quest_daily·quest_weekly), Harbor-World
 * (ChartProgressEvent: chart_percent) and Harbor-Boss (BossEncounterEndEvent: boss_clear) are
 * subscribed directly by Shop by event name, so those modules do not register the same keys again.
 * Other new sources (sea event participation, etc.) register a source when the module enables and
 * pass exp through the returned {@link PassExpSink}.
 *
 * <pre>{@code
 * Harbor.api().service(SeasonPassService.class).ifPresent(pass -> {
 *     PassExpSink sink = pass.registerSource(this, "sea_event");   // pass_s<n>.yml exp_sources.sea_event
 *     // while handling event participation:  sink.award(player, 1);
 * });
 * }</pre>
 *
 * Built-in source: {@code catch.<grade>} (Shop listens to Fishing's FishCaughtEvent directly).
 */
public interface SeasonPassService {

    /** Current level (0 to max). 0 if there is no pass this season. */
    int level(UUID player);

    /** Cumulative exp this season. */
    long exp(UUID player);

    /** 0 free · 1 premium · 2 premium+. */
    int premium(UUID player);

    /**
     * Grants the source key's exp × units. false if the source is not in the pass file or the
     * player is not online.
     *
     * @param source exp_sources key (e.g. quest_daily, catch.rare)
     */
    boolean addExp(UUID player, String source, int units);

    /**
     * Registers an exp source. Registering the same key twice is fine (same sink). The sink does
     * nothing once owner is disabled.
     */
    PassExpSink registerSource(Plugin owner, String source);

    /** Registered source keys (admin screen). */
    Set<String> sources();

    /** Hook for passing exp through a registered source. Call on the main thread. */
    interface PassExpSink {

        String source();

        /**
         * @param units number of source units (1 request = 1, 1% of chart = 1)
         * @return true if exp was granted
         */
        boolean award(UUID player, int units);
    }
}
