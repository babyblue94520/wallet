package org.example.demo.domain.payment.facade;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.payment.data.entity.DepositRecord;
import org.example.demo.domain.payment.service.DepositFacadeStats;
import org.example.demo.domain.payment.service.DepositRecord3Service;
import org.example.demo.domain.payment.service.DepositRequestParser;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DepositIgnoreAutoIncrementFacade implements DepositFacade {
    private static final int STATUS_INIT = 0;
    private static final int MAX_RETRY = 5;

    private final DepositFacadeStats depositFacadeStats;
    private final DepositRecord3Service depositRecord3Service;
    private final DepositRequestParser depositRequestParser;

    @Override
    public DepositRecord direct(String requestBody) {
        var request = depositRequestParser.parse(requestBody);

        DepositRecord depositRecord = DepositRecord.builder()
                .channelId(request.getChannelId())
                .channelOrderId(request.getChannelOrderId())
                .status(STATUS_INIT)
                .amount(request.getAmount())
                .requestBody(requestBody)
                .build();

        return insertWithRetry(depositRecord, 1);
    }

    private DepositRecord insertWithRetry(DepositRecord depositRecord, int attempt) {
        DepositRecord existing = findAndRecord(depositRecord.getChannelId(), depositRecord.getChannelOrderId());
        if (existing != null) {
            return existing;
        }
        depositRecord.setCreatedAt(System.currentTimeMillis());
        try {
            int affect = insertAndRecord(depositRecord);
            if (affect == 1) {
                return depositRecord;
            }
            return insertWithRetry(depositRecord, attempt + 1);
        } catch (DataAccessException e) {
            if (isDeadlock(e)) {
                depositFacadeStats.recordDeadlock();
            } else if (isDuplicateKey(e)) {
                existing = findAndRecord(depositRecord.getChannelId(), depositRecord.getChannelOrderId());
                if (existing != null) {
                    return existing;
                }
            } else {
                throw e;
            }

            if (attempt >= MAX_RETRY) {
                throw e;
            }
            depositFacadeStats.recordRetry();
            return insertWithRetry(depositRecord, attempt + 1);
        }
    }

    private int insertAndRecord(DepositRecord depositRecord) {
        long insertStartedAtNs = System.nanoTime();
        int affect = depositRecord3Service.insert(depositRecord);
        long insertMs = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - insertStartedAtNs));
        depositFacadeStats.recordInsertMs(insertMs);
        return affect;
    }

    private DepositRecord findAndRecord(String channelId, String channelOrderId) {
        long findStartedAtNs = System.nanoTime();
        DepositRecord depositRecord = depositRecord3Service.find(channelId, channelOrderId);
        long findMs = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - findStartedAtNs));
        depositFacadeStats.recordFindMs(findMs);
        return depositRecord;
    }

    private boolean isDeadlock(DataAccessException e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Deadlock found when trying to get lock")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isDuplicateKey(DataAccessException e) {
        Throwable current = e;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase();
                if (normalized.contains("duplicate entry")
                    || normalized.contains("duplicate key")
                    || normalized.contains("unique constraint")
                    || normalized.contains("unique index")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
