package cn.petshop.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import cn.petshop.common.enums.CardBizType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("card_ledger")
public class CardLedger {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long cardId;
    private CardBizType bizType;
    private Integer times;
    private String bizNo;
    private String operator;
    private String remark;
    private LocalDateTime createdAt;

    public static CardLedger of(Long cardId, CardBizType type, int times, String bizNo, String operator) {
        CardLedger l = new CardLedger();
        l.setCardId(cardId);
        l.setBizType(type);
        l.setTimes(times);
        l.setBizNo(bizNo);
        l.setOperator(operator);
        l.setCreatedAt(LocalDateTime.now());
        return l;
    }
}
