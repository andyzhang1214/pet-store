package cn.petshop.settle.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("settle_bill")
public class SettleBill {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String settleNo;
    private Long apptId;
    private Long memberId;
    private Long storeId;
    private String items;
    private String discountDesc;
    private BigDecimal total;
    private BigDecimal manualPrice;
    private String manualReason;
    private String payType;
    private String payDetail;
    private String operator;
    private LocalDateTime createdAt;
}
