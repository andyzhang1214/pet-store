package cn.petshop.admin.auth;

import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * code2session：开发期 mock=true 直接由 code 派生 openid；
 * 接入真实小程序时替换为 HTTP 调用（appid/secret 走配置）。
 */
@Slf4j
@Service
public class WxAuthService {

    @Value("${petshop.wx.mock:true}")
    private boolean mock;

    @Value("${petshop.wx.appid:}")
    private String appid;

    public String openid(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "code 不能为空");
        }
        if (mock) {
            return "mock-" + code;
        }
        throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "微信 code2session 未配置，请先设置 petshop.wx.appid/secret");
    }
}
