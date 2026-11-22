package wiki.creeper.harbor.season.api;

import java.time.Instant;

/**
 * One owned title (titles row).
 *
 * @param season season obtained (for display; titles are not reset by season)
 */
public record OwnedTitle(TitleId title, int season, Instant obtainedAt) {}
