package org.example.demo.domain.payment.data.repository;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.payment.data.entity.DepositRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class DepositRecordRepository {
    private final JdbcTemplate jdbcTemplate;

    public int insert(DepositRecord depositRecord) {
        return jdbcTemplate.update(
                """
                        INSERT INTO deposit_record 
                               (id, created_at, channel_id, channel_order_id, status, amount, request_body) 
                               VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                depositRecord.getId(),
                depositRecord.getCreatedAt(),
                depositRecord.getChannelId(),
                depositRecord.getChannelOrderId(),
                depositRecord.getStatus(),
                depositRecord.getAmount(),
                depositRecord.getRequestBody()
        );
    }

    public DepositRecord findByChannel(String channelId, String channelOrderId) {
        List<DepositRecord> rows = jdbcTemplate.query(
                "SELECT id, created_at, channel_id, channel_order_id, status, amount, request_body " +
                        "FROM deposit_record WHERE channel_id = ? AND channel_order_id = ? LIMIT 1",
                (rs, rowNum) -> DepositRecord.builder()
                        .id(rs.getString("id"))
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
