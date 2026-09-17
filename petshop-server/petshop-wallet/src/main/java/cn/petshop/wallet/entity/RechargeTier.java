package cn.petshop.wallet.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 充值档位。
 *
 * <h3>它承担的是「赠送金额的唯一出处」</h3>
 * 赠送金额属于门店让利，必须由配置决定，不能由客户端在充值请求里填。
 * 改造之前 {@code RechargeController} 直接收请求体里的 {@code gift}（仅校验"不超过本金"），
 * 意味着前端（或任何拿到会员令牌的人）可以自己决定送多少 ——
 * 这张表在库里躺着，却对实际入账金额没有约束力。
 * 现在改为服务端按 {@code amount} 查启用档位取 {@code gift}，档位表成为唯一出处。
 */
@Data
@TableName("recharge_tier")
public class RechargeTier {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    /** 本金，必须 > 0 */
    private BigDecimal amount;
    /** 赠送金额，必须 >= 0 且不得超过本金（充值送得比本金多没有商业解释） */
    private BigDecimal gift;
    /** 1 启用 / 0 停用。停用档位不可再下单，已生成的支付单不受影响。 */
    private Integer enabled;

    public static final int ENABLED = 1;
    public static final int DISABLED = 0;
}
