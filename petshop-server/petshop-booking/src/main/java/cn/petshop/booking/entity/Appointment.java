package cn.petshop.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("appointment")
public class Appointment {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String apptNo;
    private Long storeId;
    private Long memberId;
    private Long petId;
    private Long serviceId;
    private Long specId;
    private Long slotId;
    private BigDecimal price;
    private String guaranteeType;
    private BigDecimal guaranteeAmount;
    private Long cardId;
    private String status;
    private String verifyCode;
    private Integer vaccinePending;
    private LocalDateTime cancelDeadline;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
