package cn.petshop.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Data
@TableName("time_slot")
public class TimeSlot {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private Long serviceId;
    private LocalDate slotDate;
    private LocalTime startTime;
    private Integer capacity;
    private Integer bookedCount;
    private LocalDateTime createdAt;
}
