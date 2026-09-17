package cn.petshop.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

@Data
@TableName("service_price")
public class ServicePrice {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long serviceId;
    private Long specId;
    private BigDecimal price;
}
