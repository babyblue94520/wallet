package org.example.demo.domain.wallet.facade;

import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.example.demo.domain.wallet.infrastructure.vo.CashRequest;

import java.util.List;

public interface WalletFacade {
    WalletHistory cashIn(CashRequest request);

    WalletHistory cashOut(CashRequest request);

    List<WalletHistory> batchCashIn(List<CashRequest> requests);

    List<WalletHistory> batchCashOut(List<CashRequest> requests);
}
