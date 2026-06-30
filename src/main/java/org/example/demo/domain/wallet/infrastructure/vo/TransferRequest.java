package org.example.demo.domain.wallet.infrastructure.vo;

public record TransferRequest(
        Long payerId,
        Long payeeId,
        Long amount
) {
}

