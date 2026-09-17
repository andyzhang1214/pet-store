package cn.petshop.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务项目实体。类名避开 Spring 的 org.springframework.stereotype.Service 同名冲突。
 */
@Data
@TableName("service")
public class ServiceItem {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private String name;
    private String category;
    private Integer durationSlots;
    private Integer capacity;
    private String status;
    private LocalDateTime createdAt;

    /** 可预约 —— 唯一可下单的状态（与 01_schema.sql 的 DDL 默认值一致） */
    public static final String STATUS_ENABLED = "ENABLED";
    /** 停用：会员端不再展示、不可新建预约；已存在的预约不受影响 */
    public static final String STATUS_DISABLED = "DISABLED";
}
