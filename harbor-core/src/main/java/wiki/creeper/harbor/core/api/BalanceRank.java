package wiki.creeper.harbor.core.api;

import wiki.creeper.harbor.common.model.PlayerRef;

/**
 * 잔액 순위 한 줄 ({@link CurrencyService#top}, 추가 제안).
 *
 * @param rank 1부터 시작하는 순위
 * @param player 플레이어 (이름은 마지막 접속 때 이름)
 * @param balance 이번 시즌 잔액
 */
public record BalanceRank(int rank, PlayerRef player, long balance) {}
