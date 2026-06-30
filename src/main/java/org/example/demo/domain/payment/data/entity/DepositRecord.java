package org.example.demo.domain.payment.data.entity;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Builder
public class DepositRecord {
    private String id;
    private Long createdAt;
    private String channelId;
    private String channelOrderId;
    private Integer status;
    private Long amount;
    private String requestBody;
    private Long insertMs;
}
