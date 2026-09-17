package cn.petshop.admin.auth;

import cn.petshop.common.context.AdminContext;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.util.JwtUtil;
import cn.petshop.common.web.R;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台登录与本人身份。
 *
 * <p>{@code /admin/auth/login} 是 {@code /admin/**} 下唯一不要求已登录的端点
 * （见 {@code WebConfig} 的排除列表），其余两个走正常鉴权。
 * 登录接口同时被 {@code RateLimitFilter} 纳入严格配额（10s / 5 次），
 * 再叠加 {@code AdminAuthService} 的按账号失败锁定。
 */
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @Value("${petshop.jwt.secret}")
    private String jwtSecret;

    /** 后台令牌有效期。比会员令牌（24h）短：后台令牌能动钱，暴露窗口越小越好，半班一换也可接受。 */
    @Value("${petshop.admin.token-ttl-hours:8}")
    private long tokenTtlHours;

    @Data
    public static class LoginCmd {
        private String username;
        private String password;
    }

    /**
     * 账号口令登录，签发后台令牌。
     *
     * <p>返回体里只有身份信息，绝不含口令或口令哈希 ——
     * 实体上的 {@code @JsonIgnore} 与这里手工拼 Map 是双重保险：
     * 手工拼 Map 让"新增了实体字段就自动泄漏"这件事不可能发生。
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginCmd cmd) {
        AdminUser user = adminAuthService.login(
                cmd == null ? null : cmd.getUsername(),
                cmd == null ? null : cmd.getPassword());
        String token = JwtUtil.signAdmin(user.getId(), tokenTtlHours * 3_600_000L, jwtSecret);
        return R.ok(profileOf(user, token));
    }

    /** 当前登录身份。前端刷新页面后用它确认会话仍然有效，并拿到最新的角色。 */
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        AdminContext.Principal p = AdminContext.require();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("adminId", p.id());
        out.put("username", p.username());
        out.put("displayName", p.displayName());
        out.put("role", p.role() == null ? null : p.role().name());
        out.put("roleLabel", p.role() == null ? null : p.role().getLabel());
        out.put("sharedToken", p.sharedToken());
        out.put("mustChangePassword", p.mustChangePassword());
        return R.ok(out);
    }

    @Data
    public static class ChangePasswordCmd {
        private String oldPassword;
        private String newPassword;
    }

    /**
     * 本人改口令。共享令牌模式下没有账号，此接口无意义，直接拒绝而不是假装成功。
     */
    @PostMapping("/password")
    public R<Void> changePassword(@RequestBody ChangePasswordCmd cmd) {
        AdminContext.Principal p = AdminContext.require();
        if (p.sharedToken() || p.id() == null) {
            return R.fail(1025, "当前是共享令牌会话，没有可修改的账号口令；请用账号登录后操作");
        }
        adminAuthService.changePassword(p.id(),
                cmd == null ? null : cmd.getOldPassword(),
                cmd == null ? null : cmd.getNewPassword());
        return R.ok();
    }

    private Map<String, Object> profileOf(AdminUser user, String token) {
        AdminRole role = AdminRole.parse(user.getRole());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("adminId", user.getId());
        out.put("username", user.getUsername());
        out.put("displayName", user.getDisplayName());
        out.put("role", role == null ? user.getRole() : role.name());
        out.put("roleLabel", role == null ? user.getRole() : role.getLabel());
        out.put("mustChangePassword", user.mustChangePassword());
        return out;
    }
}
