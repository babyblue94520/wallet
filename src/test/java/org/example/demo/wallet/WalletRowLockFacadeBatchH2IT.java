package org.example.demo.wallet;

import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.example.demo.domain.wallet.facade.WalletRowLockFacade;
import org.example.demo.domain.wallet.infrastructure.vo.CashRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("h2")
class WalletRowLockFacadeBatchH2IT {

    @Autowired
    WalletRowLockFacade walletRowLockFacade;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDb() {
        jdbcTemplate.update("DELETE FROM wallet_history");
        jdbcTemplate.update("DELETE FROM wallet");
    }

    @Test
    void batchCashIn_shouldGroupByUserAndProcessEachGroupInSingleTransaction() {
        jdbcTemplate.update("INSERT INTO wallet(id, balance) VALUES (?, ?)", 1001L, 0L);
        jdbcTemplate.update("INSERT INTO wallet(id, balance) VALUES (?, ?)", 1002L, 10L);

        List<WalletHistory> histories = walletRowLockFacade.batchCashIn(List.of(
                new CashRequest(1001L, 30L),
                new CashRequest(1002L, 5L),
                new CashRequest(1001L, 20L)
        ));

        assertEquals(3, histories.size());
        assertEquals(0L, histories.get(0).getBeforeBalance());
        assertEquals(30L, histories.get(0).getAfterBalance());
        assertEquals(30L, histories.get(1).getBeforeBalance());
        assertEquals(50L, histories.get(1).getAfterBalance());
        assertEquals(10L, histories.get(2).getBeforeBalance());
        assertEquals(15L, histories.get(2).getAfterBalance());

        Long user1Balance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, 1001L);
        Long user2Balance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, 1002L);
        Integer historyCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_history", Integer.class);

        assertEquals(50L, user1Balance);
        assertEquals(15L, user2Balance);
        assertEquals(3, historyCount);
    }

    @Test
    void batchCashOut_shouldRollbackCurrentUserGroupWhenAnyRequestFails() {
        jdbcTemplate.update("INSERT INTO wallet(id, balance) VALUES (?, ?)", 2001L, 100L);
        jdbcTemplate.update("INSERT INTO wallet(id, balance) VALUES (?, ?)", 2002L, 100L);

        try {
            walletRowLockFacade.batchCashOut(List.of(
                    new CashRequest(2001L, 30L),
                    new CashRequest(2001L, 80L),
                    new CashRequest(2002L, 10L)
            ));
        } catch (Exception ignored) {
        }

        Long user1Balance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, 2001L);
        Long user2Balance = jdbcTemplate.queryForObject("SELECT balance FROM wallet WHERE id = ?", Long.class, 2002L);
        Integer historyCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_history", Integer.class);

        assertEquals(100L, user1Balance);
        assertEquals(100L, user2Balance);
        assertEquals(0, historyCount);
    }
}
