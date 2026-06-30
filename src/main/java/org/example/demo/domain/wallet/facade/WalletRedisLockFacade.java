package org.example.demo.domain.wallet.facade;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.wallet.data.entity.Wallet;
import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.example.demo.domain.wallet.data.repository.WalletRepository;
import org.example.demo.domain.wallet.infrastructure.constant.WalletAction;
import org.example.demo.domain.wallet.infrastructure.exception.*;
import org.example.demo.domain.wallet.infrastructure.vo.CashRequest;
import org.example.demo.domain.wallet.service.WalletFacadeStats;
import org.example.demo.domain.wallet.service.WalletHistoryService;
import org.example.demo.domain.wallet.service.WalletService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WalletRedisLockFacade implements WalletFacade {

    private final RedissonClient redissonClient;
    private final WalletRepository walletRepository;
    private final WalletHistoryService walletHistoryService;
    private final WalletService walletService;
    private final WalletFacadeStats walletFacadeStats;
    private final TransactionTemplate transactionTemplate;

    @Override
    public WalletHistory cashIn(CashRequest request) {
        validateCash(request);
        RedisTiming redisTiming = new RedisTiming();
        try {
            return updateWithLockAndVersion(request.userId(), request.amount(), true, redisTiming);
        } finally {
            recordRedisTotalMs(redisTiming);
        }
    }

    @Override
    public WalletHistory cashOut(CashRequest request) {
        validateCash(request);
        RedisTiming redisTiming = new RedisTiming();
        try {
            return updateWithLockAndVersion(request.userId(), request.amount(), false, redisTiming);
        } finally {
            recordRedisTotalMs(redisTiming);
        }
    }

    @Override
    public List<WalletHistory> batchCashIn(List<CashRequest> requests) {
        return batchCash(requests, true);
    }

    @Override
    public List<WalletHistory> batchCashOut(List<CashRequest> requests) {
        return batchCash(requests, false);
    }

    private WalletHistory updateWithLockAndVersion(Long userId, long amount, boolean isCashIn, RedisTiming redisTiming) {
        LockHandle lockHandle = acquireLock(userId, redisTiming);
        try {
            return transactionTemplate.execute(status -> doCashInOut(userId, amount, isCashIn));
        } finally {
            unlock(lockHandle, redisTiming);
        }
    }

    private List<WalletHistory> batchCash(List<CashRequest> requests, boolean isCashIn) {
        if (requests == null || requests.isEmpty()) {
            throw new WalletException("requests is required");
        }

        Map<Long, List<CashRequest>> requestsByUserId = requests.stream()
                .peek(WalletRedisLockFacade::validateCash)
                .collect(Collectors.groupingBy(CashRequest::userId, LinkedHashMap::new, Collectors.toList()));

        RedisTiming redisTiming = new RedisTiming();
        try {
            List<WalletHistory> histories = new ArrayList<>(requests.size());
            for (Map.Entry<Long, List<CashRequest>> entry : requestsByUserId.entrySet()) {
                histories.addAll(updateBatchWithLockAndVersion(entry.getKey(), entry.getValue(), isCashIn, redisTiming));
            }
            return histories;
        } finally {
            recordRedisTotalMs(redisTiming);
        }
    }

    private List<WalletHistory> updateBatchWithLockAndVersion(
            Long userId,
            List<CashRequest> requests,
            boolean isCashIn,
            RedisTiming redisTiming
    ) {
        LockHandle lockHandle = acquireLock(userId, redisTiming);
        try {
            List<WalletHistory> histories = transactionTemplate.execute(status ->
                    doBatchCashInOut(userId, requests, isCashIn)
            );
            if (histories == null) {
                throw new WalletException("Batch cash transaction failed.");
            }
            return histories;
        } finally {
            unlock(lockHandle, redisTiming);
        }
    }

    private WalletHistory doCashInOut(Long userId, long amount, boolean isCashIn) {
        Wallet wallet = findAndRecord(userId);
        if (wallet == null) {
            throw new WalletException("wallet not found: " + userId);
        }
        long time = System.currentTimeMillis();

        long before = wallet.getBalance() == null ? 0L : wallet.getBalance();
        long after;
        int action;
        if (isCashIn) {
            after = before + amount;
            action = WalletAction.IN;
        } else {
            if (before < amount) {
                throw new InsufficientBalanceException("Insufficient balance.");
            }
            after = before - amount;
            action = WalletAction.OUT;
        }

        long version = wallet.getVersion() == null ? 0L : wallet.getVersion();
        int affected = updateBalanceAndRecord(userId, after, version);
        if (affected != 1) {
            throw new NoAffectedException(String.format(
                    "version: %s real: %s",
                    version,
                    findAndRecord(userId).getVersion()
            ));
        }

        WalletHistory history = WalletHistory.builder()
                .createdAt(time)
                .userId(userId)
                .action(action)
                .beforeBalance(before)
                .afterBalance(after)
                .build();
        insertHistoryAndRecord(history);
        return history;
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

    private int updateBalanceAndRecord(Long userId, Long balance, Long version, long versionDelta) {
        long updateStartedAtNs = System.nanoTime();
        int affected = walletService.updateBalanceWithVersion(userId, balance, version, versionDelta);
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

    private void insertHistoriesAndRecord(List<WalletHistory> histories) {
        long insertStartedAtNs = System.nanoTime();
        walletHistoryService.batchInsert(histories);
        long insertMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - insertStartedAtNs));
        walletFacadeStats.recordInsertMs(insertMs);
    }

    private List<WalletHistory> doBatchCashInOut(Long userId, List<CashRequest> requests, boolean isCashIn) {
        Wallet wallet = findAndRecord(userId);
        if (wallet == null) {
            throw new WalletException("wallet not found: " + userId);
        }

        long currentBalance = wallet.getBalance() == null ? 0L : wallet.getBalance();
        long version = wallet.getVersion() == null ? 0L : wallet.getVersion();
        List<WalletHistory> histories = new ArrayList<>(requests.size());
        for (CashRequest request : requests) {
            long before = currentBalance;
            long after;
            int action;
            if (isCashIn) {
                after = before + request.amount();
                action = WalletAction.IN;
            } else {
                if (before < request.amount()) {
                    throw new InsufficientBalanceException("Insufficient balance.");
                }
                after = before - request.amount();
                action = WalletAction.OUT;
            }

            histories.add(WalletHistory.builder()
                    .createdAt(System.currentTimeMillis())
                    .userId(userId)
                    .action(action)
                    .beforeBalance(before)
                    .afterBalance(after)
                    .build());
            currentBalance = after;
        }

        int affected = updateBalanceAndRecord(userId, currentBalance, version, requests.size());
        if (affected != 1) {
            throw new NoAffectedException(String.format(
                    "version: %s real: %s",
                    version,
                    findAndRecord(userId).getVersion()
            ));
        }
        insertHistoriesAndRecord(histories);
        return histories;
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

    private static void validateCash(CashRequest request) {
        if (request == null) {
            throw new WalletException("request is required");
        }
        if (request.userId() == null) {
            throw new WalletException("user_id is required");
        }
        if (request.amount() == null || request.amount() <= 0) {
            throw new WalletException("amount must be > 0");
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
