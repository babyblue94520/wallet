package org.example.demo.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.example.demo.domain.payment.data.entity.DepositRecord;
import org.example.demo.domain.payment.data.repository.DepositRecordRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DepositRecordService {

    private final DepositRecordRepository depositRecordRepository;

    public DepositRecord find(String channelId, String channelOrderId) {
        return depositRecordRepository.findByChannel(channelId, channelOrderId);
    }

    public int insert(DepositRecord depositRecord) {
        return depositRecordRepository.insert(depositRecord);
    }

}
