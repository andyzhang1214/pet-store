package cn.petshop.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品订单。
 *
 * <h3>状态机</h3>
 * <pre>
 *   CREATED ──支付成功──→ PAID ──店员核销──→ VERIFIED（终态）
 *      │                   │
 *      └──取消──→ CANCELLED └──退款审核通过──→ REFUNDED（终态）
 * </pre>
 * 每条迁移都是 CAS（见 {@code GoodsOrderMapper}），并发下只有一个能成功。
 *
 * <h3>支付构成</h3>
 * {@link #principalPaid} / {@link #giftPaid} 记录这笔订单实际从「本金」和「赠送金」里各扣了多少，
 * 两者之和恒等于 {@link #total}。下单支付时按会员账户的累计比例拆分并<b>同时写入账本流水</b>，
 * 所以退款时能按原比例原样退回，而不是按当前余额比例重算（那会在两次充值赠送比例不同时算错）。
 */
@Data
@TableName("goods_order")
public class GoodsOrder {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String orderNo;
    private Long storeId;
    private Long memberId;
    /** 订单总额 */
    private BigDecimal total;
    /** 实际扣减的本金 */
    private BigDecimal principalPaid;
    /** 实际扣减的赠送金 */
    private BigDecimal giftPaid;
    /** 支付方式，取值见下方 PAY_* 常量 */
    private String payType;
    /** 订单状态，取值见下方 STATUS_* 常量 */
    private String status;
    /** 自提核销码（8 位），发单时生成，唯一 */
    private String verifyCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ---------- 状态 ----------
    /** 待支付（下单即锁库存） */
    public static final String STATUS_CREATED = "CREATED";
    /** 已支付待自提（可申请退款的唯一状态） */
    public static final String STATUS_PAID = "PAID";
    /** 已核销（终态，不支持线上退款） */
    public static final String STATUS_VERIFIED = "VERIFIED";
    /** 已取消（终态，库存已回补） */
    public static final String STATUS_CANCELLED = "CANCELLED";
    /** 已退款（终态，库存已回补） */
    public static final String STATUS_REFUNDED = "REFUNDED";

    // ---------- 支付方式 ----------
    /** 余额支付：下单即刻扣款，无中间态 */
    public static final String PAY_BALANCE = "BALANCE";
    /** 微信支付：经 pay_order 与回调入账 */
    public static final String PAY_WXPAY = "WXPAY";

    /** 是否可申请退款：只有「已支付且未核销」可以。 */
    public static boolean isRefundable(GoodsOrder order) {
        return order != null && STATUS_PAID.equals(order.getStatus());
    }
}
