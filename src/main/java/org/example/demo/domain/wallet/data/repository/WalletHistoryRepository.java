package org.example.demo.domain.wallet.data.repository;

import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

@Repository
public class WalletHistoryRepository {
    private final JdbcTemplate jdbcTemplate;

    public WalletHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int insert(WalletHistory history) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int rows = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO wallet_history(created_at, user_id, action, before_balance, after_balance) VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"}
            );
            statement.setLong(1, history.getCreatedAt());
            statement.setLong(2, history.getUserId());
            statement.setInt(3, history.getAction());
            statement.setLong(4, history.getBeforeBalance());
            statement.setLong(5, history.getAfterBalance());
            return statement;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key != null) {
            history.setId(key.longValue());
        }
        return rows;
    }

    public int[][] batchInsert(List<WalletHistory> histories) {
        if (histories == null || histories.isEmpty()) {
            return new int[0][];
        }
        return jdbcTemplate.batchUpdate(
                "INSERT INTO wallet_history(created_at, user_id, action, before_balance, after_balance) VALUES (?, ?, ?, ?, ?)",
                histories,
                histories.size(),
                (statement, history) -> {
                    statement.setLong(1, history.getCreatedAt());
                    statement.setLong(2, history.getUserId());
                    statement.setInt(3, history.getAction());
                    statement.setLong(4, history.getBeforeBalance());
                    statement.setLong(5, history.getAfterBalance());
                }
        );
    }
}
