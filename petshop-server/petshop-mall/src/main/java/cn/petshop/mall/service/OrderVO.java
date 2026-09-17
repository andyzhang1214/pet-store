package cn.petshop.mall.service;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品订单展示对象。
 *
 * {@code refundable} 由服务端判定（已支付且未核销），前端只负责渲染 —— 与次卡
 * {@code MemberCardVO.refundable} 同一口径：规则变化时不会出现前后端各说各话。
 *
 * {@code status} / {@code payType} 返回原始枚举值，中文文案由前端映射，
 * 与小程序 {@code CARD_STATUS} / {@code REFUND_STATUS} 的处理方式保持一致。
 */
@Data
public class OrderVO {
    private Long id;
    private String orderNo;
    /** 订单总额 */
    private BigDecimal total;
    /** 实扣本金 */
    private BigDecimal principalPaid;
    /** 实扣赠送金 */
    private BigDecimal giftPaid;
    private String payType;
    private String status;
    /** 自提核销码，已核销后置空（不再需要出示） */
    private String verifyCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<ItemVO> items;
    /** 是否可发起退款申请 */
    private boolean refundable;

    @Data
    public static class ItemVO {
        private Long skuId;
        private String name;
        private Integer qty;
        private BigDecimal price;
    }
}
