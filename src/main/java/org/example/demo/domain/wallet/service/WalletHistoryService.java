package org.example.demo.domain.wallet.service;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.example.demo.domain.wallet.data.repository.WalletHistoryRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WalletHistoryService {
    private final WalletHistoryRepository walletHistoryRepository;

    public int insert(WalletHistory history) {
        return walletHistoryRepository.insert(history);
    }

    public int[][] batchInsert(List<WalletHistory> histories) {
        return walletHistoryRepository.batchInsert(histories);
    }
}
