package cn.petshop.common.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 配置变更审计流水。<b>一行 = 一个字段的一次变化。</b>
 *
 * <h3>为什么需要一张独立的审计表</h3>
 * 配置域里的这些对象 —— 服务价目、卡类型、充值档位、会员等级折扣、门店规则、商品价格 ——
 * 有个共同点：<b>改了不会立刻产生一笔账，但会改变之后每一笔账</b>。
 * 库存变动有 {@code inventory_log}、余额有 {@code balance_ledger}，而"把猫洗澡从 120 改成 150"
 * 这件事此前没有任何落点，出事时只能说"系统里就是 150"。
 *
 * <p>审计表不追求可回滚（那需要把配置做成版本化事件流，代价大得多），
 * 只求三件事：<b>改了什么（前后值）、谁改的（操作人）、什么时候</b>。</p>
 *
 * <h3>为什么是「按字段一行」而不是「一对象一 JSON 快照」</h3>
 * JSON 快照要引入序列化依赖，而且读审计的人得自己在两坨 JSON 里找差异 ——
 * 而审计的全部价值就是那个差异。按字段落行之后，后台直接列成
 * 「价格 120.00 → 150.00」这样一张表，不需要前端做任何解析。
 * 代价是一次改动插多行，而配置变更的频率是"人工点几下".
 */
@Data
@TableName("config_audit_log")
public class ConfigAuditLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /** 配置对象类型，见 TARGET_* 常量 */
    private String targetType;
    /** 配置对象主键。新建时为已生成的新 id。 */
    private Long targetId;
    /** 动作：CREATE / UPDATE / STATUS / ADJUST */
    private String action;
    /** 被改动的字段名（业务语义名，如「价格」）。整体新建/调整时为 null。 */
    private String fieldName;
    /** 变更前值。新建、或从空值变更时为空。 */
    private String beforeValue;
    /** 变更后值。删除语义时为空。 */
    private String afterValue;
    /** 操作人（由服务端从后台令牌解析，请求体里没有这个字段） */
    private String operator;
    /**
     * 变更说明。
     *
     * <p>库存盘点等"数字变了但单据不存在"的场景<b>必填</b> ——
     * 这类流水是「货少了但不知道去哪了」唯一能查的线索。
     */
    private String remark;
    private LocalDateTime createdAt;

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_STATUS = "STATUS";
    public static final String ACTION_ADJUST = "ADJUST";

    public static final String TARGET_GOODS = "GOODS";
    public static final String TARGET_SKU = "SKU";
    public static final String TARGET_SERVICE = "SERVICE";
    public static final String TARGET_SERVICE_PRICE = "SERVICE_PRICE";
    public static final String TARGET_CARD_TYPE = "CARD_TYPE";
    /** 单张次卡的人工调次（补次 / 扣次），不是卡类型配置 */
    public static final String TARGET_CARD = "CARD";
    public static final String TARGET_RECHARGE_TIER = "RECHARGE_TIER";
    public static final String TARGET_MEMBER_LEVEL = "MEMBER_LEVEL";
    public static final String TARGET_STORE_CONFIG = "STORE_CONFIG";
}
