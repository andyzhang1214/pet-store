package cn.petshop.admin.auth;

import cn.petshop.common.annotation.RequireRole;
import cn.petshop.common.context.AdminContext;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.common.web.R;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 后台账号维护。整类要求店长角色 —— 能改别人角色的人当然自己也得是店长，
 * 否则店员之间可以互相提权，角色体系形同虚设。
 *
 * <p>接口里没有「删除账号」，只有「停用」。原因不是技术做不到，而是删了之后
 * 同名账号会被重新创建，而流水里的 {@code operator} 记的是显示名 ——
 * 于是"三个月前那笔人工退款是谁做的"就再也分不清了。
 * 停用能把人挡在门外，同时留下 {@code last_login_at} / {@code created_at} 这些线索。
 */
@RestController
@RequestMapping("/admin/accounts")
@RequiredArgsConstructor
@RequireRole(AdminRole.MANAGER)
public class AdminAccountController {

    private final AdminAuthService adminAuthService;

    @GetMapping
    public R<List<AdminUser.AdminUserVO>> list() {
        return R.ok(adminAuthService.listAll().stream().map(AdminUser.AdminUserVO::of).toList());
    }

    @Data
    public static class CreateCmd {
        private String username;
        private String displayName;
        /** MANAGER / STAFF */
        private String role;
        private String initialPassword;
    }

    @PostMapping
    public R<AdminUser.AdminUserVO> create(@RequestBody CreateCmd cmd) {
        if (cmd == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "缺少请求内容");
        }
        AdminUser created = adminAuthService.createAccount(
                cmd.getUsername(), cmd.getDisplayName(), AdminRole.parse(cmd.getRole()),
                cmd.getInitialPassword(), 1L);
        return R.ok(AdminUser.AdminUserVO.of(created));
    }

    @Data
    public static class UpdateCmd {
        private String displayName;
        /** MANAGER / STAFF；不传表示不改 */
        private String role;
        /** 不传表示不改 */
        private Boolean enabled;
    }

    @PutMapping("/{id}")
    public R<AdminUser.AdminUserVO> update(@PathVariable Long id, @RequestBody UpdateCmd cmd) {
        if (cmd == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "缺少请求内容");
        }
        AdminRole role = cmd.getRole() == null || cmd.getRole().isBlank()
                ? null : AdminRole.parse(cmd.getRole());
        if (cmd.getRole() != null && !cmd.getRole().isBlank() && role == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "角色只能是 MANAGER 或 STAFF");
        }
        AdminUser updated = adminAuthService.updateAccount(
                AdminContext.require().id(), id, cmd.getDisplayName(), role, cmd.getEnabled());
        return R.ok(AdminUser.AdminUserVO.of(updated));
    }

    /**
     * 重置他人口令。重置后该账号被标记为「需修改初始口令」，
     * 下次登录必须先自己改一次，避免"重置出来的口令被长期当共享口令用"。
     */
    @PostMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String newPassword = body == null ? null : body.get("newPassword");
        adminAuthService.resetPassword(id, newPassword);
        return R.ok();
    }
}
