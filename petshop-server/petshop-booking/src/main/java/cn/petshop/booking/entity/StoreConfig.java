package cn.petshop.booking.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("store_config")
public class StoreConfig {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private String name;
    private Integer cancelHours;
    private Boolean noGuaranteeEnabled;
    private String noShowPolicy;
    private Integer slotCapacity;
    private Integer bookableDays;
    private String businessHours;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static final String NO_SHOW_RECORD = "RECORD";
    public static final String NO_SHOW_DEDUCT_ALL = "DEDUCT_ALL";
    public static final String NO_SHOW_DEDUCT_HALF = "DEDUCT_HALF";
    public static final String NO_SHOW_CARD_CONFIRM = "CARD_CONFIRM";
    public static final String NO_SHOW_CARD_RELEASE = "CARD_RELEASE";
}
