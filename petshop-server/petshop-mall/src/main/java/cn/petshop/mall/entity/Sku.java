package cn.petshop.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("sku")
public class Sku {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long goodsId;
    private String name;
    private BigDecimal price;
    private Integer stock;
}
