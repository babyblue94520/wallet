package org.example.demo.domain.wallet.facade;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.wallet.data.entity.Wallet;
import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.example.demo.domain.wallet.infrastructure.constant.WalletAction;
import org.example.demo.domain.wallet.infrastructure.exception.InsufficientBalanceException;
import org.example.demo.domain.wallet.infrastructure.exception.WalletException;
import org.example.demo.domain.wallet.infrastructure.vo.TransferRequest;
import org.example.demo.domain.wallet.service.WalletFacadeStats;
import org.example.demo.domain.wallet.service.WalletHistoryService;
import org.example.demo.domain.wallet.service.WalletService;
import org.springframework.data.util.Pair;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TransferRowLockFacade implements TransferFacade {
    private final WalletService walletService;
    private final WalletHistoryService walletHistoryService;
    private final WalletFacadeStats walletFacadeStats;

    @Override
    @Transactional
    public List<WalletHistory> transfer(TransferRequest request) {
        validateTransfer(request);
        Long payerId = request.payerId();
        Long payeeId = request.payeeId();
        Long amount = request.amount();
        walletService.forUpdate(payerId, payeeId);

        int payerAffected = decrementBalanceAndRecord(payerId, amount);
        if (payerAffected == 0) {
            throw new InsufficientBalanceException("Insufficient balance.");
        }
        int payeeAffected = incrementBalanceAndRecord(payeeId, amount);
        if (payeeAffected == 0) {
            throw new WalletException("Cash in failed.");
        }

        long payerTime = System.currentTimeMillis();
        var payerWallet = findAndRecord(payerId);
        if (payerWallet == null) {
            throw new WalletException("Wallet not found.");
        }
        var payerHistory = addHistory(
                payerId,
                WalletAction.OUT,
                payerTime,
                Pair.of(payerWallet.getBalance() + amount, payerWallet.getBalance())
        );

        long payeeTime = System.currentTimeMillis();
        var payeeWallet = findAndRecord(payeeId);
        if (payeeWallet == null) {
            throw new WalletException("Wallet not found.");
        }
        var payeeHistory = addHistory(
                payeeId,
                WalletAction.IN,
                payeeTime,
                Pair.of(payeeWallet.getBalance() - amount, payeeWallet.getBalance())
        );

        return List.of(payerHistory, payeeHistory);
    }

    private WalletHistory addHistory(Long id, Integer action, long time, Pair<Long, Long> balanceChanges) {
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
}
