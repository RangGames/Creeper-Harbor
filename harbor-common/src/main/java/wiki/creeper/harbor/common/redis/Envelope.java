package wiki.creeper.harbor.common.redis;

import java.time.Instant;

/**
 * Message envelope sent over channels. Wire format is {@code {type, server, sentAt, payload}} JSON.
 *
 * @param type message record name
 * @param server sending server ID
 */
public record Envelope<T>(String type, String server, Instant sentAt, T payload) {}
