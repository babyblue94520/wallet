package org.example.demo.domain.wallet.data.repository;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.wallet.data.entity.Wallet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class WalletRepository {
    private final JdbcTemplate jdbcTemplate;

    public boolean existsById(Long id) {
        Integer v = jdbcTemplate.queryForObject("SELECT 1 FROM wallet WHERE id = ? LIMIT 1", Integer.class, id);
        return v != null;
    }

    public int incrementBalance(Long id, long delta) {
        return jdbcTemplate.update("UPDATE wallet SET balance = balance + ? WHERE id = ?", delta, id);
    }

    public int decrementBalance(Long id, long delta) {
        return jdbcTemplate.update(
                "UPDATE wallet SET balance = balance - ? WHERE id = ? AND balance >= ?",
                delta,
                id,
                delta
        );
    }

    public Wallet findById(Long id) {
        List<Wallet> rows = jdbcTemplate.query(
                "SELECT id, balance, version FROM wallet WHERE id = ?",
                (rs, rowNum) -> {
                    Wallet wallet = new Wallet();
                    wallet.setId(rs.getLong("id"));
                    wallet.setBalance(rs.getLong("balance"));
                    wallet.setVersion(rs.getLong("version"));
                    return wallet;
                },
                id
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }


    public int updateBalanceWithVersion(Long id, long newBalance, long expectedVersion) {
        return jdbcTemplate.update(
                "UPDATE wallet SET balance = ?, version = version + 1 WHERE id = ? AND version = ?",
                newBalance,
                id,
                expectedVersion
        );
    }

    public int updateBalanceWithVersion(Long id, long newBalance, long expectedVersion, long versionDelta) {
        return jdbcTemplate.update(
                "UPDATE wallet SET balance = ?, version = version + ? WHERE id = ? AND version = ?",
                newBalance,
                versionDelta,
                id,
                expectedVersion
        );
    }

    public int updateBalance(Long id, long newBalance) {
        return jdbcTemplate.update(
                "UPDATE wallet SET balance = ? WHERE id = ?",
                newBalance,
                id
        );
    }

    public void forUpdate(Long[] ids) {
        if (ids == null || ids.length == 0) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.length, "?"));
        jdbcTemplate.queryForList(
                "SELECT id FROM wallet WHERE id IN (" + placeholders + ") ORDER BY ID FOR UPDATE",
//                "SELECT id FROM wallet WHERE id IN (" + placeholders + ") FOR UPDATE",
                Long.class,
                (Object[]) ids
        );
    }
}
