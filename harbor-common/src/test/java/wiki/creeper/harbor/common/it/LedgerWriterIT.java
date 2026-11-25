package wiki.creeper.harbor.common.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;
import wiki.creeper.harbor.common.db.Database;
import wiki.creeper.harbor.common.db.Migrations;
import wiki.creeper.harbor.common.db.WriteBehindQueue;
import wiki.creeper.harbor.common.ledger.BalanceStore;
import wiki.creeper.harbor.common.ledger.LedgerAuditor;
import wiki.creeper.harbor.common.ledger.LedgerEntry;
import wiki.creeper.harbor.common.ledger.LedgerError;
import wiki.creeper.harbor.common.ledger.LedgerReason;
import wiki.creeper.harbor.common.ledger.LedgerWriter;
import wiki.creeper.harbor.common.model.Currency;
import wiki.creeper.harbor.common.util.Result;

/** Tests the ledger and write-behind against a real MariaDB container. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class LedgerWriterIT {

    @Container static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.4");

    static Database db;
    static LedgerWriter writer;

    /** Test balance store (test_wallet table). */
    static final class TestWallet implements BalanceStore {
        @Override
        public long lockBalance(Connection c, UUID player, Currency currency) throws SQLException {
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "INSERT INTO test_wallet (uuid, balance) VALUES (?, 0)"
                                    + " ON DUPLICATE KEY UPDATE uuid = uuid")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    c.prepareStatement(
                            "SELECT balance FROM test_wallet WHERE uuid = ? FOR UPDATE")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }

        @Override
        public void writeBalance(
                Connection c, UUID player, Currency currency, long newBalance, long delta)
                throws SQLException {
            try (PreparedStatement ps =
                    c.prepareStatement("UPDATE test_wallet SET balance = ? WHERE uuid = ?")) {
                ps.setLong(1, newBalance);
                ps.setString(2, player.toString());
                ps.executeUpdate();
            }
        }

        @Override
        public Map<UUID, Long> allBalances(Connection c, Currency currency) throws SQLException {
            Map<UUID, Long> result = new HashMap<>();
            try (PreparedStatement ps =
                            c.prepareStatement("SELECT uuid, balance FROM test_wallet");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(UUID.fromString(rs.getString(1)), rs.getLong(2));
                }
            }
            return result;
        }
    }

    @BeforeAll
    static void setUp() throws SQLException {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MARIADB.getJdbcUrl());
        config.setUsername(MARIADB.getUsername());
        config.setPassword(MARIADB.getPassword());
        config.setMaximumPoolSize(10);
        db = Database.wrap(new HikariDataSource(config));
        Migrations.migrate(db.dataSource(), "common", LedgerWriterIT.class.getClassLoader());
        db.txVoid(
                c -> {
                    try (var st = c.createStatement()) {
                        st.execute(
                                "CREATE TABLE test_wallet (uuid UUID PRIMARY KEY, balance BIGINT NOT NULL)");
                        st.execute(
                                "CREATE TABLE test_kv (k VARCHAR(64) PRIMARY KEY, v INT NOT NULL)");
                    }
                });
        writer = new LedgerWriter(Map.of(Currency.GOLD, new TestWallet()));
    }

    @AfterAll
    static void tearDown() {
        db.close();
    }

    @Test
    void grantsAndRejectsNegativeBalance() {
        UUID player = UUID.randomUUID();
        assertEquals(
                100L,
                writer.post(
                                db,
                                new LedgerEntry(
                                        player, Currency.GOLD, 100, LedgerReason.ADMIN_GRANT, null))
                        .orThrow());
        assertEquals(
                Result.err(LedgerError.INSUFFICIENT_FUNDS),
                writer.post(
                        db,
                        new LedgerEntry(player, Currency.GOLD, -101, LedgerReason.SHOP_BUY, null)));
        assertEquals(
                40L,
                writer.post(
                                db,
                                new LedgerEntry(
                                        player, Currency.GOLD, -60, LedgerReason.SHOP_BUY, null))
                        .orThrow());
    }

    @Test
    void setPostsDiffAgainstLockedBalance() {
        UUID player = UUID.randomUUID();
        writer.post(db, new LedgerEntry(player, Currency.GOLD, 70, LedgerReason.FISH_SELL, null))
                .orThrow();
        LedgerWriter.SetOutcome up =
                writer.postSet(
                                db,
                                player,
                                Currency.GOLD,
                                100,
                                LedgerReason.ADMIN_GRANT,
                                LedgerReason.ADMIN_TAKE,
                                null)
                        .orThrow();
        assertEquals(100L, up.balance());
        LedgerEntry upEntry = up.entry();
        assertTrue(upEntry != null && upEntry.delta() == 30);
        LedgerWriter.SetOutcome same =
                writer.postSet(
                                db,
                                player,
                                Currency.GOLD,
                                100,
                                LedgerReason.ADMIN_GRANT,
                                LedgerReason.ADMIN_TAKE,
                                null)
                        .orThrow();
        assertEquals(new LedgerWriter.SetOutcome(null, 100L), same);
        LedgerWriter.SetOutcome down =
                writer.postSet(
                                db,
                                player,
                                Currency.GOLD,
                                0,
                                LedgerReason.ADMIN_GRANT,
                                LedgerReason.ADMIN_TAKE,
                                null)
                        .orThrow();
        assertEquals(0L, down.balance());
        LedgerEntry downEntry = down.entry();
        assertTrue(downEntry != null && downEntry.reason() == LedgerReason.ADMIN_TAKE);
    }

    @Test
    void sameReferenceIsPaidOnce() {
        UUID player = UUID.randomUUID();
        LedgerEntry reward =
                new LedgerEntry(
                        player, Currency.GOLD, 500, LedgerReason.QUEST_REWARD, "quest-done-1");
        assertTrue(writer.post(db, reward).isOk());
        assertEquals(Result.err(LedgerError.DUPLICATE), writer.post(db, reward));
    }

    @Test
    void transferIsAtomic() {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        writer.post(db, new LedgerEntry(buyer, Currency.GOLD, 50, LedgerReason.ADMIN_GRANT, null))
                .orThrow();

        Result<List<Long>, LedgerError> result =
                writer.postAll(
                        db,
                        List.of(
                                new LedgerEntry(
                                        seller,
                                        Currency.GOLD,
                                        80,
                                        LedgerReason.AUCTION_SALE,
                                        "sale-1"),
                                new LedgerEntry(
                                        buyer,
                                        Currency.GOLD,
                                        -80,
                                        LedgerReason.AUCTION_BUY,
                                        "sale-1")));

        assertEquals(Result.err(LedgerError.INSUFFICIENT_FUNDS), result);
        // The seller payout must be cancelled too
        assertEquals(
                Result.ok(30L),
                writer.post(
                        db,
                        new LedgerEntry(
                                seller, Currency.GOLD, 30, LedgerReason.ADMIN_GRANT, null)));
    }

    @Test
    void concurrentWritesKeepLedgerAndBalanceInSync() throws Exception {
        UUID player = UUID.randomUUID();
        List<CompletableFuture<Result<Long, LedgerError>>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(
                    CompletableFuture.supplyAsync(
                            () ->
                                    writer.post(
                                            db,
                                            new LedgerEntry(
                                                    player,
                                                    Currency.GOLD,
                                                    10,
                                                    LedgerReason.FISH_SELL,
                                                    null)),
                            db.executor()));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new)).get();

        assertTrue(futures.stream().allMatch(f -> f.join().isOk()));
        assertTrue(LedgerAuditor.audit(db, Currency.GOLD, new TestWallet()).isEmpty());
    }

    @Test
    void writeBehindKeepsOnlyLatestValuePerKey() throws SQLException {
        WriteBehindQueue queue = new WriteBehindQueue(db);
        for (int i = 1; i <= 3; i++) {
            int value = i;
            queue.enqueue(
                    "kv:a",
                    c -> {
                        try (PreparedStatement ps =
                                c.prepareStatement(
                                        "INSERT INTO test_kv (k, v) VALUES ('a', ?) ON DUPLICATE KEY UPDATE v = VALUES(v)")) {
                            ps.setInt(1, value);
                            ps.executeUpdate();
                        }
                    });
        }
        assertEquals(1, queue.pendingCount());
        queue.close();

        int stored =
                db.tx(
                        c -> {
                            try (var st = c.createStatement();
                                    ResultSet rs =
                                            st.executeQuery(
                                                    "SELECT v FROM test_kv WHERE k = 'a'")) {
                                rs.next();
                                return rs.getInt(1);
                            }
                        });
        assertEquals(3, stored);
    }
}
