package org.example.demo.domain.payment.infrastructure.exception;

public class DepositLockRetryExceededException extends RuntimeException {
    public DepositLockRetryExceededException(String message) {
        super(message);
    }
}
