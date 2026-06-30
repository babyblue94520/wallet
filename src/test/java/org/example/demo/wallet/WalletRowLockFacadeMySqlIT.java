package org.example.demo.wallet;

import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.example.demo.domain.wallet.facade.WalletRowLockFacade;
import org.example.demo.domain.wallet.infrastructure.vo.CashRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("mysql-it")
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_IT", matches = "true")
class WalletRowLockFacadeMySqlIT {

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
    void cashIn_cashOut_shouldWriteHistory_inMySqlLocalhost3306() {
        long userId = 9001L;
        jdbcTemplate.update("INSERT INTO wallet(id, balance) VALUES (?, ?)", userId, 0L);

        WalletHistory in = walletRowLockFacade.cashIn(new CashRequest(userId, 100L));
        assertNotNull(in.getId());
        assertEquals(userId, in.getUserId());
        assertEquals(0L, in.getBeforeBalance());
        assertEquals(100L, in.getAfterBalance());

        WalletHistory out = walletRowLockFacade.cashOut(new CashRequest(userId, 40L));
        assertNotNull(out.getId());
        assertEquals(userId, out.getUserId());
        assertEquals(100L, out.getBeforeBalance());
        assertEquals(60L, out.getAfterBalance());

        Integer historyCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallet_history", Integer.class);
        assertEquals(2, historyCount);
    }
}

