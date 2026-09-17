package cn.petshop.member.entity;

import cn.petshop.common.enums.HealthLogType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("pet_health_log")
public class PetHealthLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private Long petId;
    private HealthLogType logType;
    private LocalDate logDate;
    private LocalDate nextDue;
    private BigDecimal weightValue;
    private String source;
    private String bizNo;
    private String operator;
    private String remark;
    private LocalDateTime createdAt;

    public static final String SOURCE_SERVICE = "SERVICE";
    public static final String SOURCE_STAFF = "STAFF";
}
