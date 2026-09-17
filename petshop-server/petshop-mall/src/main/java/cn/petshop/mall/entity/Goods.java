package cn.petshop.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("goods")
public class Goods {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private String name;
    private String category;
    private String images;
    private String description;
    private BigDecimal priceFrom;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 上架 —— 唯一可下单的状态 */
    public static final String STATUS_ON_SALE = "ON_SALE";
    /** 下架 */
    public static final String STATUS_OFF_SALE = "OFF_SALE";
}
