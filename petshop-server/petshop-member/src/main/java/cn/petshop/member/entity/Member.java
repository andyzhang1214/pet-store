package cn.petshop.member.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("member")
public class Member {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private String openid;
    private String phone;
    private String nickname;
    private Integer level;
    private BigDecimal totalPaid;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
