package org.example.demo.domain.wallet.infrastructure.exception;

public class WalletOperationTimeoutException extends WalletException {
    public WalletOperationTimeoutException(String message) {
        super(message);
    }
}
