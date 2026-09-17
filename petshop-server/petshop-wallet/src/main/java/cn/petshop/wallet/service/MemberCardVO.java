package cn.petshop.wallet.service;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 会员名下次卡展示对象（钱包页"我的次卡"）。
 *
 * {@code refundable} 由服务端判定 —— 前端只负责渲染，不自己算"能不能退"，
 * 否则一旦规则变化（部分使用、有预扣、过期口径）前后端就会各说各话。
 */
@Data
public class MemberCardVO {
    private Long id;
    private String cardNo;
    /** 卡类型名，如"猫咪洗澡10次卡" */
    private String name;
    private Integer totalTimes;
    private Integer remainTimes;
    private Integer preDeductCount;
    private LocalDate validUntil;
    private String status;
    /** 购买实付金额，退款以此为准 */
    private BigDecimal paidAmount;
    /** 是否可发起整卡退款（未使用、无预扣、未过期） */
    private boolean refundable;
}
