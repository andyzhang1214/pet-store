package cn.petshop.admin.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 推送留痕。唯一索引 {@code (biz_no, template_id)} 是「同一笔退款不会重复推给店长」的防线：
 * 重复触发时 INSERT 撞唯一索引即跳过发送，避免店长被同一笔退款轰炸两次。
 */
@Data
@TableName("notify_log")
public class NotifyLog {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String openid;
    private String templateId;
    private String bizNo;
    /** 取值见下方 STATUS_* 常量 */
    private String status;
    private String errMsg;
    private LocalDateTime createdAt;

    /** 推送成功（已消耗一次配额） */
    public static final String STATUS_SUCCESS = "SUCCESS";
    /** 无可用配额 —— 店长没授权或已用完，属正常降级 */
    public static final String STATUS_SKIPPED_NO_QUOTA = "SKIPPED_NO_QUOTA";
    /** 模板 ID / 凭据未配置 —— 属正常降级，不影响退款主流程 */
    public static final String STATUS_SKIPPED_NOT_CONFIGURED = "SKIPPED_NOT_CONFIGURED";
    /** 已推送过同一笔（撞唯一索引） */
    public static final String STATUS_SKIPPED_DUPLICATE = "SKIPPED_DUPLICATE";
    /** 微信接口返回失败 */
    public static final String STATUS_FAILED = "FAILED";
}
