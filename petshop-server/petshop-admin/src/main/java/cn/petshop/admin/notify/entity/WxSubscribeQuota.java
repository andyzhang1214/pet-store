package cn.petshop.admin.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 店长订阅授权额度。
 *
 * <p>微信订阅消息是<b>一次性授权</b>：店长每点一次「允许通知」才得到一次推送配额，
 * 推成功即消耗一次。这与公众号模板消息可以无限推完全不同 ——
 * 所以必须把剩余次数记下来，归零后自动降级为「只发后台角标」，而不是让推送静默失败。
 */
@Data
@TableName("wx_subscribe_quota")
public class WxSubscribeQuota {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String openid;
    private String templateId;
    /** 剩余可推送次数 */
    private Integer remainCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
