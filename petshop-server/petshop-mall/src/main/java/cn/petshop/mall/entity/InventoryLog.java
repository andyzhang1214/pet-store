package cn.petshop.mall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("inventory_log")
public class InventoryLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long skuId;
    private String bizType;
    private Integer qty;
    private Integer stockAfter;
    private String bizNo;
    private String operator;
    private LocalDateTime createdAt;

    /** 销售出库：下单锁库存，{@code qty} 为负 */
    public static final String BIZ_SALE = "SALE";
    /** 回补入库：取消 / 退款 / 超时关单，{@code qty} 为正 */
    public static final String BIZ_RESTORE = "RESTORE";
    /**
     * 人工盘点调整：{@code qty} 可正可负。
     *
     * <p>与 SALE / RESTORE 的区别在于它<b>没有对应的业务单据</b>（{@code biz_no} 为空），
     * 所以原因必须由操作人写明 —— 这类流水是"货少了但不知道去哪了"唯一能查的线索。
     */
    public static final String BIZ_ADJUST = "ADJUST";
}
