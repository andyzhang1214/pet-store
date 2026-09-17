package cn.petshop.admin.auth;

import cn.petshop.common.context.StoreContext;

import cn.petshop.common.util.JwtUtil;
import cn.petshop.common.web.R;
import cn.petshop.member.entity.Member;
import cn.petshop.member.service.MemberService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final WxAuthService wxAuthService;
    private final MemberService memberService;

    @Value("${petshop.jwt.secret}")
    private String jwtSecret;

    @Value("${petshop.jwt.ttl-hours}")
    private long jwtTtlHours;

    @Data
    public static class LoginCmd {
        private String code;
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginCmd cmd) {
        String openid = wxAuthService.openid(cmd.getCode());
        Member member = memberService.findOrCreate(openid, StoreContext.DEFAULT_STORE_ID);
        String token = JwtUtil.sign(member.getId(), jwtTtlHours * 3_600_000L, jwtSecret);
        return R.ok(Map.of(
                "token", token,
                "memberId", member.getId(),
                "nickname", member.getNickname() == null ? "" : member.getNickname(),
                "level", member.getLevel()
        ));
    }
}
