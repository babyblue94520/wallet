package org.example.demo.domain.payment.infrastructure.exception;

public class InvalidDepositRequestException extends RuntimeException {
    public InvalidDepositRequestException(String message) {
        super(message);
    }
}
