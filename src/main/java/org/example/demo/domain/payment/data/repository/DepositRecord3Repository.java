package org.example.demo.domain.payment.data.repository;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.payment.data.entity.DepositRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class DepositRecord3Repository {
    private final JdbcTemplate jdbcTemplate;

    public int insert(DepositRecord depositRecord) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        int affected = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                            INSERT INTO deposit_record3
                                   (created_at, channel_id, channel_order_id, status, amount, request_body)
                                   VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    new String[]{"id"}
            );
            statement.setLong(1, depositRecord.getCreatedAt());
            statement.setString(2, depositRecord.getChannelId());
            statement.setString(3, depositRecord.getChannelOrderId());
            statement.setInt(4, depositRecord.getStatus());
            statement.setLong(5, depositRecord.getAmount());
            statement.setString(6, depositRecord.getRequestBody());
            return statement;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (affected == 1 && key != null) {
            depositRecord.setId(String.valueOf(key.longValue()));
        }
        return affected;
    }

    public DepositRecord findByChannel(String channelId, String channelOrderId) {
        List<DepositRecord> rows = jdbcTemplate.query(
                "SELECT id, created_at, channel_id, channel_order_id, status, amount, request_body " +
                        "FROM deposit_record3 WHERE channel_id = ? AND channel_order_id = ? ORDER BY created_at ASC, id ASC LIMIT 1",
                (rs, rowNum) -> DepositRecord.builder()
                        .id(String.valueOf(rs.getLong("id")))
                        .createdAt(rs.getLong("created_at"))
                        .channelId(rs.getString("channel_id"))
                        .channelOrderId(rs.getString("channel_order_id"))
                        .status(rs.getInt("status"))
                        .amount(rs.getLong("amount"))
                        .requestBody(rs.getString("request_body"))
                        .build(),
                channelId,
                channelOrderId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
