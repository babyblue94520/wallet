package org.example.demo.domain.wallet.facade;

import org.example.demo.domain.wallet.data.entity.WalletHistory;
import org.example.demo.domain.wallet.infrastructure.vo.TransferRequest;

import java.util.List;

public interface TransferFacade {
    List<WalletHistory> transfer(TransferRequest request);
}
