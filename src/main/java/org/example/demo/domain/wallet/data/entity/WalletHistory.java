package org.example.demo.domain.wallet.data.entity;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@Builder
public class WalletHistory {
    private Long id;
    private Long createdAt;
    private Long userId;
    private Integer action;
    private Long beforeBalance;
    private Long afterBalance;
}
