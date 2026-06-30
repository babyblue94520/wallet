package org.example.demo.domain.wallet.facade;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.wallet.data.entity.Wallet;
import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.example.demo.domain.wallet.infrastructure.constant.WalletAction;
import org.example.demo.domain.wallet.infrastructure.exception.InsufficientBalanceException;
import org.example.demo.domain.wallet.infrastructure.exception.WalletException;
import org.example.demo.domain.wallet.infrastructure.vo.CashRequest;
import org.example.demo.domain.wallet.service.WalletFacadeStats;
import org.example.demo.domain.wallet.service.WalletHistoryService;
import org.example.demo.domain.wallet.service.WalletService;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WalletRowLockFacade implements WalletFacade {
    private final WalletService walletService;
    private final WalletHistoryService walletHistoryService;
    private final WalletFacadeStats walletFacadeStats;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional
    public WalletHistory cashIn(CashRequest request) {
        return cashInWithinTransaction(request);
    }


    @Override
    @Transactional
    public WalletHistory cashOut(CashRequest request) {
        return cashOutWithinTransaction(request);
    }

    @Override
    public List<WalletHistory> batchCashIn(List<CashRequest> requests) {
        return batchCash(requests, true);
    }

    @Override
    public List<WalletHistory> batchCashOut(List<CashRequest> requests) {
        return batchCash(requests, false);
    }

    private WalletHistory addHistory(
            Long id,
            Integer action,
            long time,
            Pair<Long, Long> balanceChanges
    ) {
        var entity = WalletHistory.builder()
                .createdAt(time)
                .userId(id)
                .action(action)
                .beforeBalance(balanceChanges.getFirst())
                .afterBalance(balanceChanges.getSecond())
                .build();
        insertHistoryAndRecord(entity);
        return entity;
    }

    private Wallet findAndRecord(Long userId) {
        long findStartedAtNs = System.nanoTime();
        var wallet = walletService.findById(userId);
        long findMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - findStartedAtNs));
        walletFacadeStats.recordFindMs(findMs);
        return wallet;
    }

    private void insertHistoryAndRecord(WalletHistory entity) {
        long insertStartedAtNs = System.nanoTime();
        walletHistoryService.insert(entity);
        long insertMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - insertStartedAtNs));
        walletFacadeStats.recordInsertMs(insertMs);
    }

    private void insertHistoriesAndRecord(List<WalletHistory> histories) {
        long insertStartedAtNs = System.nanoTime();
        walletHistoryService.batchInsert(histories);
        long insertMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - insertStartedAtNs));
        walletFacadeStats.recordInsertMs(insertMs);
    }

    private int incrementBalanceAndRecord(Long userId, Long amount) {
        long updateStartedAtNs = System.nanoTime();
        int affected = walletService.incrementBalance(userId, amount);
        long updateBalanceMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - updateStartedAtNs));
        walletFacadeStats.recordUpdateBalanceMs(updateBalanceMs);
        return affected;
    }

    private int decrementBalanceAndRecord(Long userId, Long amount) {
        long updateStartedAtNs = System.nanoTime();
        int affected = walletService.decrementBalance(userId, amount);
        long updateBalanceMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - updateStartedAtNs));
        walletFacadeStats.recordUpdateBalanceMs(updateBalanceMs);
        return affected;
    }

    private int updateBalanceAndRecord(Long userId, long balance) {
        long updateStartedAtNs = System.nanoTime();
        int affected = walletService.updateBalance(userId, balance);
        long updateBalanceMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - updateStartedAtNs));
        walletFacadeStats.recordUpdateBalanceMs(updateBalanceMs);
        return affected;
    }

    private WalletHistory cashInWithinTransaction(CashRequest request) {
        validateCash(request);
        Long userId = request.userId();
        Long amount = request.amount();

        int affected = incrementBalanceAndRecord(userId, amount);
        if (affected == 0) {
            throw new WalletException("Cash in failed.");
        }
        long time = System.currentTimeMillis();
        var wallet = findAndRecord(userId);
        if (wallet == null) {
            throw new WalletException("Wallet not found.");
        }
        var changes = Pair.of(wallet.getBalance() - amount, wallet.getBalance());
        return addHistory(userId, WalletAction.IN, time, changes);
    }

    private WalletHistory cashOutWithinTransaction(CashRequest request) {
        validateCash(request);
        Long userId = request.userId();
        Long amount = request.amount();

        int affected = decrementBalanceAndRecord(userId, amount);
        if (affected == 0) {
            throw new InsufficientBalanceException("Insufficient balance.");
        }
        long time = System.currentTimeMillis();
        var wallet = findAndRecord(userId);
        if (wallet == null) {
            throw new WalletException("Wallet not found.");
        }
        var changes = Pair.of(wallet.getBalance() + amount, wallet.getBalance());
        return addHistory(userId, WalletAction.OUT, time, changes);
    }

    private List<WalletHistory> batchCash(List<CashRequest> requests, boolean isCashIn) {
        if (requests == null || requests.isEmpty()) {
            throw new WalletException("requests is required");
        }

        Map<Long, List<CashRequest>> requestsByUserId = requests.stream()
                .peek(WalletRowLockFacade::validateCash)
                .collect(Collectors.groupingBy(CashRequest::userId, LinkedHashMap::new, Collectors.toList()));

        List<WalletHistory> histories = new ArrayList<>(requests.size());
        for (Map.Entry<Long, List<CashRequest>> entry : requestsByUserId.entrySet()) {
            List<WalletHistory> groupHistories = transactionTemplate.execute(status -> {
                walletService.forUpdate(entry.getKey());
                return processBatchCashGroup(entry.getKey(), entry.getValue(), isCashIn);
            });

            if (groupHistories == null) {
                throw new WalletException("Batch cash transaction failed.");
            }
            histories.addAll(groupHistories);
        }
        return histories;
    }

    private List<WalletHistory> processBatchCashGroup(Long userId, List<CashRequest> requests, boolean isCashIn) {
        Wallet wallet = findAndRecord(userId);
        if (wallet == null) {
            throw new WalletException("Wallet not found.");
        }

        long currentBalance = wallet.getBalance() == null ? 0L : wallet.getBalance();
        List<WalletHistory> histories = new ArrayList<>(requests.size());
        for (CashRequest request : requests) {
            long amount = request.amount();
            long beforeBalance = currentBalance;
            long afterBalance;
            int action;

            if (isCashIn) {
                afterBalance = beforeBalance + amount;
                action = WalletAction.IN;
            } else {
                if (beforeBalance < amount) {
                    throw new InsufficientBalanceException("Insufficient balance.");
                }
                afterBalance = beforeBalance - amount;
                action = WalletAction.OUT;
            }

            histories.add(WalletHistory.builder()
                    .createdAt(System.currentTimeMillis())
                    .userId(userId)
                    .action(action)
                    .beforeBalance(beforeBalance)
                    .afterBalance(afterBalance)
                    .build());
            currentBalance = afterBalance;
        }

        int affected = updateBalanceAndRecord(userId, currentBalance);
        if (affected != 1) {
            throw new WalletException("Batch cash update failed.");
        }
        insertHistoriesAndRecord(histories);
        return histories;
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
}
