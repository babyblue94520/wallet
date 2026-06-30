package org.example.demo.wallet;

import org.example.demo.domain.wallet.facade.TransferRedisLockFacade;
import org.example.demo.domain.wallet.facade.WalletRedisLockFacade;
import org.example.demo.domain.wallet.infrastructure.vo.CashRequest;
import org.example.demo.domain.wallet.infrastructure.vo.TransferRequest;
import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.example.demo.domain.wallet.service.WalletFacadeStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("mysql-it")
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_REDIS_IT", matches = "true")
class WalletRedisFacadeMySqlRedisLockIT {

    @Autowired
    WalletRedisLockFacade walletRedisLockFacade;

    @Autowired
    TransferRedisLockFacade transferRedisLockFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    WalletFacadeStats walletFacadeStats;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM wallet_history");
        jdbcTemplate.update("DELETE FROM wallet");
        stringRedisTemplate.delete("wallet:lock:9002");
        stringRedisTemplate.delete("wallet:lock:9003");
        walletFacadeStats.reset();
    }

    @Test
    void cashIn_cashOut_shouldUpdateBalanceAndVersion_andWriteHistory() {
        long userId = 9002L;
        jdbcTemplate.update("INSERT INTO wallet(id, balance, version) VALUES (?, ?, ?)", userId, 0L, 0L);

        var in = walletRedisLockFacade.cashIn(new CashRequest(userId, 100L));
        assertNotNull(in.getId());
        assertEquals(userId, in.getUserId());
        assertEquals(0L, in.getBeforeBalance());
        assertEquals(100L, in.getAfterBalance());

        var out = walletRedisLockFacade.cashOut(new CashRequest(userId, 40L));
        assertNotNull(out.getId());
        assertEquals(userId, out.getUserId());
        assertEquals(100L, out.getBeforeBalance());
        assertEquals(60L, out.getAfterBalance());

        Long balance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, userId);
        Long version = jdbcTemplate.queryForObject("SELECT version FROM wallet WHERE id = ?", Long.class, userId);
        Integer historyCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_history", Integer.class);

        assertEquals(60L, balance);
        assertEquals(2L, version); // each successful op increments version once
        assertEquals(2, historyCount);
        assertStatsRecorded();
        assertEquals(0L, walletFacadeStats.retryCount());
        assertEquals(0L, walletFacadeStats.operationTimeoutCount());
    }

    @Test
    void transfer_shouldLockByIdOrder_andUpdateBothWallets() {
        long payerId = 9003L;
        long payeeId = 9002L;
        jdbcTemplate.update("INSERT INTO wallet(id, balance, version) VALUES (?, ?, ?)", payerId, 100L, 0L);
        jdbcTemplate.update("INSERT INTO wallet(id, balance, version) VALUES (?, ?, ?)", payeeId, 20L, 0L);

        var histories = transferRedisLockFacade.transfer(new TransferRequest(payerId, payeeId, 40L));

        assertEquals(2, histories.size());
        WalletHistory payerHistory = histories.getFirst();
        WalletHistory payeeHistory = histories.get(1);

        assertEquals(payerId, payerHistory.getUserId());
        assertEquals(100L, payerHistory.getBeforeBalance());
        assertEquals(60L, payerHistory.getAfterBalance());
        assertEquals(payeeId, payeeHistory.getUserId());
        assertEquals(20L, payeeHistory.getBeforeBalance());
        assertEquals(60L, payeeHistory.getAfterBalance());

        Long payerBalance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, payerId);
        Long payeeBalance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, payeeId);
        Long payerVersion = jdbcTemplate.queryForObject("SELECT version FROM wallet WHERE id = ?", Long.class, payerId);
        Long payeeVersion = jdbcTemplate.queryForObject("SELECT version FROM wallet WHERE id = ?", Long.class, payeeId);
        Integer historyCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_history", Integer.class);

        assertEquals(60L, payerBalance);
        assertEquals(60L, payeeBalance);
        assertEquals(1L, payerVersion);
        assertEquals(1L, payeeVersion);
        assertEquals(2, historyCount);
        assertStatsRecorded();
        assertEquals(0L, walletFacadeStats.retryCount());
        assertEquals(0L, walletFacadeStats.operationTimeoutCount());
    }

    @Test
    void batchCashIn_shouldGroupByUserAndProcessEachGroupInSingleTransaction() {
        jdbcTemplate.update("INSERT INTO wallet(id, balance, version) VALUES (?, ?, ?)", 9101L, 0L, 0L);
        jdbcTemplate.update("INSERT INTO wallet(id, balance, version) VALUES (?, ?, ?)", 9102L, 10L, 0L);

        var histories = walletRedisLockFacade.batchCashIn(List.of(
                new CashRequest(9101L, 30L),
                new CashRequest(9102L, 5L),
                new CashRequest(9101L, 20L)
        ));

        assertEquals(3, histories.size());
        assertEquals(0L, histories.get(0).getBeforeBalance());
        assertEquals(30L, histories.get(0).getAfterBalance());
        assertEquals(30L, histories.get(1).getBeforeBalance());
        assertEquals(50L, histories.get(1).getAfterBalance());
        assertEquals(10L, histories.get(2).getBeforeBalance());
        assertEquals(15L, histories.get(2).getAfterBalance());

        Long user1Balance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, 9101L);
        Long user2Balance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, 9102L);
        Long user1Version = jdbcTemplate.queryForObject("SELECT version FROM wallet WHERE id = ?", Long.class, 9101L);
        Long user2Version = jdbcTemplate.queryForObject("SELECT version FROM wallet WHERE id = ?", Long.class, 9102L);
        Integer historyCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_history", Integer.class);

        assertEquals(50L, user1Balance);
        assertEquals(15L, user2Balance);
        assertEquals(2L, user1Version);
        assertEquals(1L, user2Version);
        assertEquals(3, historyCount);
        assertStatsRecorded();
    }

    @Test
    void batchCashOut_shouldRollbackCurrentUserGroupWhenAnyRequestFails() {
        jdbcTemplate.update("INSERT INTO wallet(id, balance, version) VALUES (?, ?, ?)", 9201L, 100L, 0L);
        jdbcTemplate.update("INSERT INTO wallet(id, balance, version) VALUES (?, ?, ?)", 9202L, 100L, 0L);

        assertThrows(Exception.class, () -> walletRedisLockFacade.batchCashOut(List.of(
                new CashRequest(9201L, 30L),
                new CashRequest(9201L, 80L),
                new CashRequest(9202L, 10L)
        )));

        Long user1Balance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, 9201L);
        Long user2Balance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, 9202L);
        Long user1Version = jdbcTemplate.queryForObject("SELECT version FROM wallet WHERE id = ?", Long.class, 9201L);
        Long user2Version = jdbcTemplate.queryForObject("SELECT version FROM wallet WHERE id = ?", Long.class, 9202L);
        Integer historyCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_history", Integer.class);

        assertEquals(100L, user1Balance);
        assertEquals(100L, user2Balance);
        assertEquals(0L, user1Version);
        assertEquals(0L, user2Version);
        assertEquals(0, historyCount);
        assertStatsRecorded();
    }

    private void assertStatsRecorded() {
        assertMetricRecorded(walletFacadeStats.findMinMs(), "findMinMs");
        assertMetricRecorded(walletFacadeStats.insertMinMs(), "insertMinMs");
        assertMetricRecorded(walletFacadeStats.updateBalanceMinMs(), "updateBalanceMinMs");
        assertMetricRecorded(walletFacadeStats.getLockMinMs(), "getLockMinMs");
        assertMetricRecorded(walletFacadeStats.redisTotalMinMs(), "redisTotalMinMs");
    }

    private static void assertMetricRecorded(long value, String metricName) {
        assertTrue(value > 0, metricName + " should be recorded");
    }
}
