package org.example.demo.domain.wallet.service;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.wallet.data.entity.Wallet;
import org.example.demo.domain.wallet.data.repository.WalletRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository walletRepository;

    public int incrementBalance(Long userId, Long amount) {
        if (userId == null || amount == null || amount <= 0) {
            return 0;
        }
        return walletRepository.incrementBalance(userId, amount);
    }

    public int decrementBalance(Long userId, Long amount) {
        if (userId == null || amount == null || amount <= 0) {
            return 0;
        }
        return walletRepository.decrementBalance(userId, amount);
    }

    public Wallet findById(Long id) {
        return walletRepository.findById(id);
    }

    public int updateBalanceWithVersion(Long id, long newBalance, long expectedVersion) {
        return walletRepository.updateBalanceWithVersion(id, newBalance, expectedVersion);
    }

    public int updateBalanceWithVersion(Long id, long newBalance, long expectedVersion, long versionDelta) {
        return walletRepository.updateBalanceWithVersion(id, newBalance, expectedVersion, versionDelta);
    }

    public int updateBalance(Long id, long newBalance) {
        return walletRepository.updateBalance(id, newBalance);
    }

    public void forUpdate(Long... ids) {
        Arrays.sort(ids);
        walletRepository.forUpdate(ids);
    }
}
