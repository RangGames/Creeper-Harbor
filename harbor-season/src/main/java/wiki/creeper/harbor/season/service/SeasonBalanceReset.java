package wiki.creeper.harbor.season.service;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.core.api.HarborApi;
import wiki.creeper.harbor.season.transition.ReadOnlySeasonBalances;

/** Season balance reset: zeroes balances through SEASON_RESET ledger entries in batches. */
final class SeasonBalanceReset {

    private static final int RESET_BATCH = 50;

    private SeasonBalanceReset() {}

    static long run(HarborApi api, Logger log, int season, Set<Currency> currencies)
            throws Exception {
        record Balance(UUID player, Currency currency, long amount) {}
        List<Balance> balances =
                api.database()
                        .tx(
                                c -> {
                                    List<Balance> out = new ArrayList<>();
                                    for (Currency currency : currencies) {
                                        String column = ReadOnlySeasonBalances.column(currency);
                                        try (PreparedStatement ps =
                                                c.prepareStatement(
                                                        "SELECT uuid, "
                                                                + column
                                                                + " FROM player_season WHERE season = ? AND ("
                                                                + column
                                                                + ") <> 0")) {
                                            ps.setInt(1, season);
                                            try (ResultSet rs = ps.executeQuery()) {
                                                while (rs.next()) {
                                                    out.add(
                                                            new Balance(
                                                                    UUID.fromString(
                                                                            rs.getString(1)),
                                                                    currency,
                                                                    rs.getLong(2)));
                                                }
                                            }
                                        }
                                    }
                                    return out;
                                });
        long done = 0;
        for (int i = 0; i < balances.size(); i += RESET_BATCH) {
            List<Balance> chunk = balances.subList(i, Math.min(balances.size(), i + RESET_BATCH));
            List<LedgerEntry> entries = new ArrayList<>();
            for (Balance b : chunk) {
                if (b.amount() < 0) {
                    log.error(
                            "음수 잔액은 초기화하지 않습니다 (원장 검사 필요): {} {} {}",
                            b.player(),
                            b.currency(),
                            b.amount());
                    continue;
                }
                entries.add(
                        new LedgerEntry(
                                b.player(),
                                b.currency(),
                                -b.amount(),
                                LedgerReason.SEASON_RESET,
                                ref(season, b.player())));
            }
            if (entries.isEmpty()) {
                continue;
            }
            Result<List<Long>, LedgerError> batch = api.currency().atomic(entries, null).join();
            if (batch.isOk()) {
                done += entries.size();
                continue;
            }
            // If a batch fails (some already processed by a resume etc.), process one at a time
            for (LedgerEntry e : entries) {
                Result<Long, LedgerError> one =
                        api.currency()
                                .take(
                                        e.player(),
                                        e.currency(),
                                        -e.delta(),
                                        LedgerReason.SEASON_RESET,
                                        e.refId())
                                .join();
                if (one instanceof Result.Err<Long, LedgerError>(LedgerError error)
                        && error != LedgerError.DUPLICATE) {
                    throw new IllegalStateException(
                            "SEASON_RESET 실패: " + e.player() + " " + e.currency() + " " + error);
                }
                done++;
            }
        }
        return done;
    }

    /** SEASON_RESET reference ID (docs 20 11.4: season_reset:&lt;n&gt;:&lt;uuid&gt;). */
    static String ref(int season, UUID player) {
        return "season_reset:" + season + ":" + player;
    }
}
