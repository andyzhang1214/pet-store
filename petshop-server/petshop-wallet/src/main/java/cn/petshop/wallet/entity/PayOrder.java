package cn.petshop.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("pay_order")
public class PayOrder {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String payNo;
    private Long storeId;
    private Long memberId;
    private String bizType;
    private String bizNo;
    private BigDecimal amount;
    private String channel;
    private String status;
    private String wxTransactionId;
    private String callbackRaw;
    private Integer notifyCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static final String BIZ_RECHARGE = "RECHARGE";
    public static final String BIZ_GOODS = "GOODS";
    public static final String BIZ_CARD = "CARD";
    public static final String BIZ_APPOINT_GUARANTEE = "APPOINT_GUARANTEE";
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_REFUNDED = "REFUNDED";
}
