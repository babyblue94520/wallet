package org.example.demo.domain.wallet.data.entity;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class Wallet {
    private Long id;
    private Long balance;
    private Long version;
}
