package org.example.demo.domain.wallet.infrastructure.exception;

public class InsufficientBalanceException extends WalletException {
    public InsufficientBalanceException(String message) {
        super(message);
    }
}

