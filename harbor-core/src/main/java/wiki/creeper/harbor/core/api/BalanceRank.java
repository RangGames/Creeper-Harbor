package wiki.creeper.harbor.core.api;

import wiki.creeper.harbor.common.model.PlayerRef;

/**
 * One balance ranking row ({@link CurrencyService#top}, proposed).
 *
 * @param rank rank starting at 1
 * @param player the player (name as of their last login)
 * @param balance balance for the current season
 */
public record BalanceRank(int rank, PlayerRef player, long balance) {}
