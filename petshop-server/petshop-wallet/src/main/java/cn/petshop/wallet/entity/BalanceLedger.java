package cn.petshop.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import cn.petshop.common.enums.LedgerBizType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("balance_ledger")
public class BalanceLedger {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long memberId;
    private LedgerBizType bizType;
    private BigDecimal principalAmt;
    private BigDecimal giftAmt;
    private BigDecimal balanceAfter;
    private BigDecimal frozenAfter;
    private String bizNo;
    private String remark;
    private String operator;
    private LocalDateTime createdAt;

    public static BalanceLedger of(Long memberId, LedgerBizType type, BigDecimal principal, BigDecimal gift,
                                   BigDecimal balanceAfter, BigDecimal frozenAfter, String bizNo) {
        BalanceLedger l = new BalanceLedger();
        l.setMemberId(memberId);
        l.setBizType(type);
        l.setPrincipalAmt(principal);
        l.setGiftAmt(gift);
        l.setBalanceAfter(balanceAfter);
        l.setFrozenAfter(frozenAfter);
        l.setBizNo(bizNo);
        l.setCreatedAt(LocalDateTime.now());
        return l;
    }
}
