package cn.petshop.admin.auth;

import cn.petshop.common.enums.AdminRole;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 后台操作账号。
 *
 * <p>{@link #passwordHash} 标了 {@code @JsonIgnore}，这是第二道防线 ——
 * 对外一律通过 {@link AdminUserVO} 返回，那个类里根本没有口令字段。
 * 保留这个注解是为了防止将来有人图省事直接把实体丢进返回值：
 * 「不该出现在响应里的东西，就别让它有序列化路径」。
 */
@Data
@TableName("admin_user")
public class AdminUser {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long storeId;
    private String username;
    private String displayName;

    @JsonIgnore
    private String passwordHash;

    /** 取值见 {@code AdminRole}：MANAGER / STAFF */
    private String role;

    /** 取值见下方 STATUS_* */
    private String status;

    /**
     * 初始口令未修改标记。
     *
     * <p>为 1 时服务端只放行「改口令」相关接口，其余请求一律 403 ——
     * 靠服务端拦，不靠前端弹窗提醒：前端提示只能拦住老实人，
     * 而把初始口令一直用下去的恰恰不是老实人。
     */
    private Boolean mustChangePwd;

    private LocalDateTime lastLoginAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    public boolean isEnabled() {
        return STATUS_ENABLED.equals(status);
    }

    public boolean mustChangePassword() {
        return Boolean.TRUE.equals(mustChangePwd);
    }

    /** 对外视图，不含任何口令材料。 */
    @Data
    public static class AdminUserVO {
        private Long id;
        private String username;
        private String displayName;
        private String role;
        private String roleLabel;
        private String status;
        private Boolean mustChangePwd;
        private LocalDateTime lastLoginAt;
        private LocalDateTime createdAt;

        public static AdminUserVO of(AdminUser u) {
            if (u == null) {
                return null;
            }
            AdminUserVO vo = new AdminUserVO();
            vo.setId(u.getId());
            vo.setUsername(u.getUsername());
            vo.setDisplayName(u.getDisplayName());
            vo.setRole(u.getRole());
            // 库里存的是枚举名，界面上要显示中文；解析不出来时原样返回，
            // 让"数据里有脏值"这件事在界面上看得见，而不是悄悄显示成空白
            AdminRole parsed = AdminRole.parse(u.getRole());
            vo.setRoleLabel(parsed == null ? u.getRole() : parsed.getLabel());
            vo.setStatus(u.getStatus());
            vo.setMustChangePwd(u.mustChangePassword());
            vo.setLastLoginAt(u.getLastLoginAt());
            vo.setCreatedAt(u.getCreatedAt());
            return vo;
        }
    }
}
