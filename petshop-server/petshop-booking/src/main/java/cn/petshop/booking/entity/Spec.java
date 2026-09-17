package cn.petshop.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("spec")
public class Spec {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private String petType;
    private String size;
    private String hair;
    private String name;
}
