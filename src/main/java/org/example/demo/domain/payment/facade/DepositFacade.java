package org.example.demo.domain.payment.facade;

import org.example.demo.domain.payment.data.entity.DepositRecord;

public interface DepositFacade {
    DepositRecord direct(String requestBody);
}
