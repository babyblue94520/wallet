package org.example.demo.domain.payment.facade;

import lombok.RequiredArgsConstructor;
import org.example.demo.common.util.Uuid7;
import org.example.demo.domain.payment.data.entity.DepositRecord;
import org.example.demo.domain.payment.infrastructure.exception.DepositLockRetryExceededException;
import org.example.demo.domain.payment.service.DepositFacadeStats;
import org.example.demo.domain.payment.service.DepositRecord2Service;
import org.example.demo.domain.payment.service.DepositRequestParser;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class DepositRedisLockFacade implements DepositFacade {
    private static final int STATUS_INIT = 0;
    private static final int MAX_RETRY = 5;

    private final RedissonClient redissonClient;
    private final DepositFacadeStats depositFacadeStats;
    private final DepositRecord2Service depositRecord2Service;
    private final DepositRequestParser depositRequestParser;

    @Override
    public DepositRecord direct(String requestBody) {
        var request = depositRequestParser.parse(requestBody);
        RedisTiming redisTiming = new RedisTiming();

        DepositRecord depositRecord = DepositRecord.builder()
                .channelId(request.getChannelId())
                .channelOrderId(request.getChannelOrderId())
                .status(STATUS_INIT)
                .amount(request.getAmount())
                .requestBody(requestBody)
                .build();
        String lockKey = lockKey(depositRecord.getChannelId(), depositRecord.getChannelOrderId());

        DepositRecord result = directWithRetry(depositRecord, lockKey, 1, redisTiming);
        depositFacadeStats.recordRedisTotalMs(redisTiming.totalMs());
        return result;
    }

    private DepositRecord directWithRetry(
            DepositRecord depositRecord,
            String lockKey,
            int attempt,
            RedisTiming redisTiming
    ) {
        DepositRecord existing = findAndRecord(
                depositRecord.getChannelId(),
                depositRecord.getChannelOrderId()
        );
        if (existing != null) {
            return existing;
        }

        LockHandle lockHandle = tryAcquireLockOnce(lockKey, redisTiming);
        if (lockHandle == null) {
            if (attempt >= MAX_RETRY) {
                depositFacadeStats.recordRetryExceeded();
                throw new DepositLockRetryExceededException(
                        "Retry exceeded for channel_id=" + depositRecord.getChannelId() + ", channel_order_id=" + depositRecord.getChannelOrderId()
                );
            }

            depositFacadeStats.recordRetry();
            sleepQuietly(backoff(attempt));
            return directWithRetry(depositRecord, lockKey, attempt + 1, redisTiming);
        }

        try {
            DepositRecord existingAfterLock = findAndRecord(
                    depositRecord.getChannelId(),
                    depositRecord.getChannelOrderId()
            );
            if (existingAfterLock != null) {
                return existingAfterLock;
            }

            try {
                depositRecord.setId(Uuid7.newUuidString());
                depositRecord.setCreatedAt(System.currentTimeMillis());
                int affect = insertAndRecord(depositRecord);
                if (affect == 1) {
                    return depositRecord;
                }
            } catch (DataAccessException e) {
                DepositRecord inserted = findAndRecord(
                        depositRecord.getChannelId(),
                        depositRecord.getChannelOrderId()
                );
                if (inserted != null) {
                    return inserted;
                }
                throw e;
            }
        } finally {
            unlock(lockHandle, redisTiming);
        }
        return directWithRetry(depositRecord, lockKey, attempt + 1, redisTiming);
    }

    private int insertAndRecord(DepositRecord depositRecord) {
        long insertStartedAtNs = System.nanoTime();
        int affect = depositRecord2Service.insert(depositRecord);
        long insertMs = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - insertStartedAtNs));
        depositFacadeStats.recordInsertMs(insertMs);
        return affect;
    }

    private DepositRecord findAndRecord(String channelId, String channelOrderId) {
        long findStartedAtNs = System.nanoTime();
        DepositRecord depositRecord = depositRecord2Service.find(channelId, channelOrderId);
        long findMs = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - findStartedAtNs));
        depositFacadeStats.recordFindMs(findMs);
        return depositRecord;
    }

    private LockHandle tryAcquireLockOnce(String lockKey, RedisTiming redisTiming) {
        RLock lock = redissonClient.getLock(lockKey);
        long getLockStartedAtNs = System.nanoTime();
        try {
            if (lock.tryLock(8, 10, TimeUnit.SECONDS)) {
                long getLockMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - getLockStartedAtNs));
                depositFacadeStats.recordGetLockMs(getLockMs);
                redisTiming.addMs(getLockMs);
                return new LockHandle(lock);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long getLockMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - getLockStartedAtNs));
        depositFacadeStats.recordGetLockMs(getLockMs);
        redisTiming.addMs(getLockMs);
        return null;
    }

    private void unlock(LockHandle lockHandle, RedisTiming redisTiming) {
        try {
            if (lockHandle.lock().isHeldByCurrentThread()) {
                long unlockStartedAtNs = System.nanoTime();
                lockHandle.lock().unlock();
                long unlockMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - unlockStartedAtNs));
                redisTiming.addMs(unlockMs);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static String lockKey(String channelId, String channelOrderId) {
        return "deposit:lock:" + channelId + ":" + channelOrderId;
    }

    private static Duration backoff(int attempt) {
        long ms = Math.min(200L, 10L * attempt * attempt);
        return Duration.ofMillis(ms);
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record LockHandle(RLock lock) {
    }

    private static class RedisTiming {
        private long totalMs;

        private void addMs(long ms) {
            totalMs += ms;
        }

        private long totalMs() {
            return totalMs;
        }
    }
}
