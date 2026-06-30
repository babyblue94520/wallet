package org.example.demo.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.payment.data.entity.DepositRecord;
import org.example.demo.domain.payment.data.repository.DepositRecord3Repository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DepositRecord3Service {
    private final DepositRecord3Repository depositRecord3Repository;

    public DepositRecord find(String channelId, String channelOrderId) {
        return depositRecord3Repository.findByChannel(channelId, channelOrderId);
    }

    public int insert(DepositRecord depositRecord) {
        return depositRecord3Repository.insert(depositRecord);
    }
}
