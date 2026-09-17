package cn.petshop.admin.notify.controller;

import cn.petshop.admin.notify.service.WxSubscribeService;
import cn.petshop.common.web.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台自检：店长是否真的能收到微信提醒。
 * {@code /admin/**} 由 AdminTokenInterceptor 统一鉴权。
 *
 * <p>存在意义：订阅消息的失败是<b>静默</b>的（模板 ID 没填、店长没授权、配额用尽都会安静降级），
 * 没有这个端点，店长只能靠"怎么一直没收到通知"来发现问题。
 */
@RestController
@RequestMapping("/admin/notify")
@RequiredArgsConstructor
public class AdminNotifyController {

    private final WxSubscribeService wxSubscribeService;

    @GetMapping("/status")
    public R<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        // 只要有一项没配齐，推送就是关的，此时唯一的提醒通道是后台角标
        out.put("configured", wxSubscribeService.isConfigured());
        out.put("managerCount", wxSubscribeService.managerCount());
        out.put("quota", wxSubscribeService.quotaSnapshot());
        out.put("hint", wxSubscribeService.isConfigured()
                ? "推送通道已就绪。店长每次在小程序点「允许通知」可换取一次推送额度，用完需再点。"
                : "推送通道未开启，当前只有后台角标提醒。请配置 petshop.wx.appid / secret、"
                + "petshop.notify.refund-template-id 与 petshop.notify.manager-openids。");
        return R.ok(out);
    }
}
