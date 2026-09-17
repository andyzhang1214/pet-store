package cn.petshop.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("member_level")
public class MemberLevel {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private Integer level;
    private String name;
    private BigDecimal threshold;
    private BigDecimal generalDiscount;
    private String serviceDiscount;
    private String benefits;
    private Integer enabled;
}
