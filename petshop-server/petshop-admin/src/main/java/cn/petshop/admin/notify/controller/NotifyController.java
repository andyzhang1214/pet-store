package cn.petshop.admin.notify.controller;

import cn.petshop.admin.notify.service.WxSubscribeService;
import cn.petshop.common.context.MemberContext;
import cn.petshop.common.web.R;
import cn.petshop.member.entity.Member;
import cn.petshop.member.service.MemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会员侧通知授权。
 *
 * <p>微信订阅消息的授权只能在小程序端由 {@code wx.requestSubscribeMessage} 弹出并取得用户同意，
 * 服务端拿不到这个结果，所以由前端在小程序回调成功后调本接口上报，服务端据此 +1 配额。
 *
 * <p>只有配置在店长名单里的 openid 会被记录，其余（普通顾客）静默忽略 ——
 * 否则任何顾客授权一下都能收到别人的退款单信息。
 */
@RestController
@RequestMapping("/api/v1/notify")
@RequiredArgsConstructor
public class NotifyController {

    private final WxSubscribeService wxSubscribeService;
    private final MemberService memberService;

    /**
     * 上报一次订阅授权。模板 ID 由服务端配置决定，不采信客户端传值 ——
     * 否则前端可以指向任意模板来污染配额。
     */
    @PostMapping("/subscribe")
    public R<Boolean> subscribe() {
        Long memberId = MemberContext.require();
        Member m = memberService.byId(memberId);
        if (m == null || m.getOpenid() == null || m.getOpenid().isBlank()) {
            return R.ok(false);
        }
        return R.ok(wxSubscribeService.grantQuota(m.getOpenid()));
    }
}
