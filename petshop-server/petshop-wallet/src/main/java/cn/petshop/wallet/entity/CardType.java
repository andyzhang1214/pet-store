package cn.petshop.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@TableName("card_type")
public class CardType {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private String name;
    private String serviceIds;
    private String specIds;
    private Integer times;
    private BigDecimal price;
    private String validMode;
    private Integer validMonths;
    private LocalDate validUntil;
    private String status;
}
