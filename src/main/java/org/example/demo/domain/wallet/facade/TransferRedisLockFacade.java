package org.example.demo.domain.wallet.facade;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.wallet.data.entity.Wallet;
import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.example.demo.domain.wallet.data.repository.WalletRepository;
import org.example.demo.domain.wallet.infrastructure.constant.WalletAction;
import org.example.demo.domain.wallet.infrastructure.exception.InsufficientBalanceException;
import org.example.demo.domain.wallet.infrastructure.exception.NoAffectedException;
import org.example.demo.domain.wallet.infrastructure.exception.WalletException;
import org.example.demo.domain.wallet.infrastructure.exception.WalletOperationTimeoutException;
import org.example.demo.domain.wallet.infrastructure.vo.TransferRequest;
import org.example.demo.domain.wallet.service.WalletFacadeStats;
import org.example.demo.domain.wallet.service.WalletHistoryService;
import org.example.demo.domain.wallet.service.WalletService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

@Service
@RequiredArgsConstructor
public class TransferRedisLockFacade implements TransferFacade {
    private final RedissonClient redissonClient;
    private final WalletRepository walletRepository;
    private final WalletHistoryService walletHistoryService;
    private final WalletService walletService;
    private final WalletFacadeStats walletFacadeStats;
    private final TransactionTemplate transactionTemplate;

    @Override
    public List<WalletHistory> transfer(TransferRequest request) {
        validateTransfer(request);
        RedisTiming redisTiming = new RedisTiming();
        try {
            return updateTransferWithLockAndVersion(
                    request.payerId(),
                    request.payeeId(),
                    request.amount(),
                    redisTiming
            );
        } finally {
            recordRedisTotalMs(redisTiming);
        }
    }

    private List<WalletHistory> updateTransferWithLockAndVersion(
            Long payerId,
            Long payeeId,
            long amount,
            RedisTiming redisTiming
    ) {
        long[] lockOrder = LongStream.of(payerId, payeeId).sorted().toArray();
        List<LockHandle> acquiredLocks = new ArrayList<>(2);
        try {
            for (long walletId : lockOrder) {
                acquiredLocks.add(acquireLock(walletId, redisTiming));
            }
            return transactionTemplate.execute(status -> doTransfer(payerId, payeeId, amount));
        } finally {
            unlockAll(acquiredLocks, redisTiming);
        }
    }

    private List<WalletHistory> doTransfer(Long payerId, Long payeeId, long amount) {
        Wallet payer = findAndRecord(payerId);
        if (payer == null) {
            throw new WalletException("wallet not found: " + payerId);
        }
        Wallet payee = findAndRecord(payeeId);
        if (payee == null) {
            throw new WalletException("wallet not found: " + payeeId);
        }
        if (payer.getBalance() == null || payer.getBalance() < amount) {
            throw new InsufficientBalanceException("Insufficient balance.");
        }

        long payerBefore = payer.getBalance();
        long payerAfter = payerBefore - amount;
        long payeeBefore = payee.getBalance() == null ? 0L : payee.getBalance();
        long payeeAfter = payeeBefore + amount;

        long payerVersion = payer.getVersion() == null ? 0L : payer.getVersion();
        long payeeVersion = payee.getVersion() == null ? 0L : payee.getVersion();

        int payerAffected = updateBalanceAndRecord(payerId, payerAfter, payerVersion);
        if (payerAffected != 1) {
            throw new NoAffectedException(String.format(
                    "payer version: %s real: %s",
                    payerVersion,
                    findAndRecord(payerId).getVersion()
            ));
        }

        int payeeAffected = updateBalanceAndRecord(payeeId, payeeAfter, payeeVersion);
        if (payeeAffected != 1) {
            throw new NoAffectedException(String.format(
                    "payee version: %s real: %s",
                    payeeVersion,
                    findAndRecord(payeeId).getVersion()
            ));
        }

        long time = System.currentTimeMillis();
        WalletHistory payerHistory = WalletHistory.builder()
                .createdAt(time)
                .userId(payerId)
                .action(WalletAction.OUT)
                .beforeBalance(payerBefore)
                .afterBalance(payerAfter)
                .build();
        WalletHistory payeeHistory = WalletHistory.builder()
                .createdAt(time)
                .userId(payeeId)
                .action(WalletAction.IN)
                .beforeBalance(payeeBefore)
                .afterBalance(payeeAfter)
                .build();
        insertHistoryAndRecord(payerHistory);
        insertHistoryAndRecord(payeeHistory);
        return List.of(payerHistory, payeeHistory);
    }

    private Wallet findAndRecord(Long userId) {
        long findStartedAtNs = System.nanoTime();
        Wallet wallet = walletRepository.findById(userId);
        long findMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - findStartedAtNs));
        walletFacadeStats.recordFindMs(findMs);
        return wallet;
    }

    private int updateBalanceAndRecord(Long userId, Long balance, Long version) {
        long updateStartedAtNs = System.nanoTime();
        int affected = walletService.updateBalanceWithVersion(userId, balance, version);
        long updateBalanceMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - updateStartedAtNs));
        walletFacadeStats.recordUpdateBalanceMs(updateBalanceMs);
        return affected;
    }

    private void insertHistoryAndRecord(WalletHistory history) {
        long insertStartedAtNs = System.nanoTime();
        walletHistoryService.insert(history);
        long insertMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - insertStartedAtNs));
        walletFacadeStats.recordInsertMs(insertMs);
    }

    private LockHandle acquireLock(long walletId, RedisTiming redisTiming) {
        RLock lock = redissonClient.getLock(lockKey(walletId));
        try {
            long getLockStartedAtNs = System.nanoTime();
            if (lock.tryLock(8, 10, TimeUnit.SECONDS)) {
                long getLockMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - getLockStartedAtNs));
                walletFacadeStats.recordGetLockMs(getLockMs);
                redisTiming.addMs(getLockMs);
                return new LockHandle(lock);
            }
            long getLockMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - getLockStartedAtNs));
            walletFacadeStats.recordGetLockMs(getLockMs);
            redisTiming.addMs(getLockMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        walletFacadeStats.recordOperationTimeout();
        throw new WalletOperationTimeoutException("Operation timeout while acquiring lock for walletId=" + walletId);
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

    private void unlockAll(List<LockHandle> lockHandles, RedisTiming redisTiming) {
        for (int i = lockHandles.size() - 1; i >= 0; i--) {
            unlock(lockHandles.get(i), redisTiming);
        }
    }

    private void recordRedisTotalMs(RedisTiming redisTiming) {
        walletFacadeStats.recordRedisTotalMs(redisTiming.totalMs());
    }

    private static String lockKey(long walletId) {
        return "wallet:lock:" + walletId;
    }

    private static void validateTransfer(TransferRequest request) {
        if (request == null) {
            throw new WalletException("request is required");
        }
        if (request.payerId() == null) {
            throw new WalletException("payer_id is required");
        }
        if (request.payeeId() == null) {
            throw new WalletException("payee_id is required");
        }
        if (request.payerId().equals(request.payeeId())) {
            throw new WalletException("payer_id and payee_id must be different");
        }
        if (request.amount() == null || request.amount() <= 0) {
            throw new WalletException("amount must be > 0");
        }
    }

    private record LockHandle(RLock lock) {}

    private static class RedisTiming {
        private long totalMs;
        private void addMs(long ms) { totalMs += ms; }
        private long totalMs() { return totalMs; }
    }
}
