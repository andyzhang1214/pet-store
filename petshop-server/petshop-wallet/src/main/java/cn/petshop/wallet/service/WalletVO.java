package cn.petshop.wallet.service;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WalletVO {
    private BigDecimal balance;
    private BigDecimal frozenAmount;
    private BigDecimal available;
    private BigDecimal refundablePrincipal;
}
