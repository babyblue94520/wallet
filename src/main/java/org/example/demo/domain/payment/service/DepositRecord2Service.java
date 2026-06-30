package org.example.demo.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.payment.data.entity.DepositRecord;
import org.example.demo.domain.payment.data.repository.DepositRecord2Repository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DepositRecord2Service {
    private final DepositRecord2Repository depositRecord2Repository;

    public DepositRecord find(String channelId, String channelOrderId) {
        return depositRecord2Repository.findByChannel(channelId, channelOrderId);
    }

    public int insert(DepositRecord depositRecord) {
        return depositRecord2Repository.insert(depositRecord);
    }
}
