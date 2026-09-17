package cn.petshop.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("pet")
public class Pet {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private Long memberId;
    private String name;
    private String type;
    private String size;
    private String hair;
    private String gender;
    private LocalDate birthday;
    private Boolean neutered;
    private BigDecimal weight;
    private String photos;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标记（MyBatis-Plus @TableLogic）：deleteById 自动变为 UPDATE deleted=1，
     * 所有 BaseMapper 查询自动追加 deleted=0。
     * 目的：删除宠物后历史预约 / 健康档案仍可正确追溯，不再出现 petName 为 null、
     * 旧预约无法核销结算的问题（对应审查 M-7）。
     */
    @TableLogic
    private Integer deleted;
}
