package cn.petshop.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("member")
public class MemberAccount {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private BigDecimal balance;
    private BigDecimal frozenAmount;
    private BigDecimal cumPrincipal;
    private BigDecimal cumGift;
}
