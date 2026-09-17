package cn.petshop.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@TableName("card")
public class Card {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String cardNo;
    private Long storeId;
    private Long memberId;
    private Long cardTypeId;
    private Integer totalTimes;
    private Integer remainTimes;
    private Integer preDeductCount;
    private LocalDate validUntil;
    private String status;
    /**
     * 购买实付金额。退款以它为准而不是 card_type.price —— 卡类型改价后，
     * 用现行价退款会退错金额（老卡多退 / 新卡少退）。
     */
    private BigDecimal paidAmount;

    public static final String STATUS_USING = "USING";
    public static final String STATUS_EXHAUSTED = "EXHAUSTED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_REFUNDED = "REFUNDED";
}
