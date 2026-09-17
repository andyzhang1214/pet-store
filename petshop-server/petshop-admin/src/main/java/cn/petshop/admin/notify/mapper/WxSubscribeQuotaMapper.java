package cn.petshop.admin.notify.mapper;

import cn.petshop.admin.notify.entity.WxSubscribeQuota;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface WxSubscribeQuotaMapper extends BaseMapper<WxSubscribeQuota> {

    /** 授权上报：配额 +1。店长在小程序点「允许通知」并由前端上报后调用。 */
    @Update("UPDATE wx_subscribe_quota SET remain_count = remain_count + 1, updated_at = #{at} "
            + "WHERE openid = #{openid} AND template_id = #{templateId}")
    int grantOne(@Param("openid") String openid, @Param("templateId") String templateId,
                 @Param("at") LocalDateTime at);

    /**
     * 消耗一次配额（推送成功时）。带 {@code remain_count >= 1} 条件，
     * 返回 0 表示配额已用尽，调用方按正常降级处理、不要报错。
     */
    @Update("UPDATE wx_subscribe_quota SET remain_count = remain_count - 1, updated_at = #{at} "
            + "WHERE openid = #{openid} AND template_id = #{templateId} AND remain_count >= 1")
    int consumeOne(@Param("openid") String openid, @Param("templateId") String templateId,
                   @Param("at") LocalDateTime at);
}
