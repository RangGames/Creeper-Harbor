package wiki.creeper.harbor.common.redis;

/**
 * Server-wide shared channels ('11. Data design' chapter 4). Module-specific message records live
 * in each module's api package.
 */
public final class Channels {

    public static final String ANNOUNCE = "harbor:announce";
    public static final String EVENT = "harbor:event";
    public static final String MARKET = "harbor:market";
    public static final String WORLD_BOSS = "harbor:worldboss";

    /**
     * Server-wide announcements (rare catches, new records, bosses). message is a MiniMessage
     * string.
     */
    public record Announcement(String message) {}

    /**
     * External integration events (Harbor-Web webhooks/REST, subscribed by external services,
     * proposed).
     */
    public static final Channel<wiki.creeper.harbor.common.event.ExternalEvent> EVENTS =
            new Channel<>("harbor:events", wiki.creeper.harbor.common.event.ExternalEvent.class);

    public static final Channel<Announcement> ANNOUNCEMENTS =
            new Channel<>(ANNOUNCE, Announcement.class);

    private Channels() {}
}
