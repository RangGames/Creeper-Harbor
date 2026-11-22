package wiki.creeper.harbor.fishing.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import wiki.creeper.harbor.common.redis.Channel;
import wiki.creeper.harbor.common.redis.Channels;

/**
 * harbor:event sea event start/end message (docs 20 3.2, "11. Data design" chapter 4). World's sea
 * event scheduler publishes it on {@link #CHANNEL} and Fishing subscribes to apply fish_effects to
 * rolls. Messages of other formats may be sent on the same channel (filtered by the envelope type).
 *
 * <p>If Redis disconnects, rolls continue with the last received state, and each event ends on its
 * own once {@code endsAt} passes.
 *
 * @param eventId event run ID (e.g. great_migration). Sending START again with the same ID
 *     overwrites it
 * @param state START or END
 * @param region event sea area (null for the whole server). Participants = players fishing in this
 *     region
 * @param endsAt end time (meaningful only for START)
 * @param fishEffects roll effects
 */
public record SeaEventMessage(
        String eventId,
        State state,
        @Nullable String region,
        @Nullable Instant endsAt,
        List<FishEffect> fishEffects) {

    public static final Channel<SeaEventMessage> CHANNEL =
            new Channel<>(Channels.EVENT, SeaEventMessage.class);

    /** Start/end. */
    public enum State {
        START,
        END
    }

    public SeaEventMessage {
        fishEffects = fishEffects == null ? List.of() : List.copyOf(fishEffects);
    }

    /**
     * One roll effect (proposed: {@code [{type: species_weight, fish, mult}, {type: mythic, fish,
     * chance, cap}]}).
     *
     * @param type species_weight (species-step multiplier, separate from the grade cap) or mythic
     *     (mythic window)
     * @param fish species ID (e.g. harbor:fish/migration_leader)
     * @param mult species_weight multiplier (great migration ×3)
     * @param chance mythic chance per cast (0 means balance.yml fishing.mythic.event_chance)
     * @param cap mythic server-wide maximum count per event
     */
    public record FishEffect(String type, String fish, double mult, double chance, int cap) {

        public static final String SPECIES_WEIGHT = "species_weight";
        public static final String MYTHIC = "mythic";

        public static FishEffect speciesWeight(String fish, double mult) {
            return new FishEffect(SPECIES_WEIGHT, fish, mult, 0, 0);
        }

        public static FishEffect mythic(String fish, double chance, int cap) {
            return new FishEffect(MYTHIC, fish, 1, chance, cap);
        }
    }
}
