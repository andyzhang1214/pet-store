package cn.petshop.admin.interceptor;

import cn.petshop.admin.auth.AdminAuthService;
import cn.petshop.admin.auth.AdminUser;
import cn.petshop.common.annotation.RequireRole;
import cn.petshop.common.context.AdminContext;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.util.JwtUtil;
import cn.petshop.common.web.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 后台统一鉴权。取代原先「一枚部署级共享令牌，谁拿到谁就是店长」的形态。
 *
 * <h3>两种凭证</h3>
 * <ol>
 *   <li><b>账号令牌</b>：{@code Authorization: Bearer <jwt>}，登录 {@code POST /admin/auth/login} 取得。
 *       令牌里只有管理员 id，角色与状态每次请求回库读（见 {@code AdminAuthService}），
 *       所以停用账号、调整角色都是立即生效，不必等令牌过期，也不需要维护吊销名单。</li>
 *   <li><b>共享令牌</b>（{@code X-Admin-Token}）：仅限 dev / test，
 *       给本地联调与自动化脚本用。非开发 profile 下 {@code StartupSecurityGuard} 会拒绝启动，
 *       因此线上不存在这条路径。</li>
 * </ol>
 *
 * <h3>为什么在拦截器里统一做三件事</h3>
 * 认证、强制改密、角色校验都在 {@link #preHandle} 完成。放到各个 Controller 里做，
 * 就等于赌"每个新写的接口都记得加校验"，而漏掉的那一个往往正是资金接口。
 * 角色要求由 {@link RequireRole} 注解声明，注解缺席时视为"登录即可访问"，
 * 但需要动钱的接口都显式标了 {@code MANAGER}。
 */
@Component
@RequiredArgsConstructor
public class AdminTokenInterceptor implements HandlerInterceptor {

    /** 允许在"必须先改初始口令"状态下访问的路径前缀 */
    private static final String AUTH_PATH_PREFIX = "/admin/auth";

    private final AdminAuthService adminAuthService;

    @Value("${petshop.jwt.secret}")
    private String jwtSecret;

    @Value("${petshop.admin.token:}")
    private String sharedToken;

    /** 是否允许共享令牌登录。非 dev/test 下必须为 false，否则启动守卫会拒绝启动。 */
    @Value("${petshop.admin.shared-token-enabled:true}")
    private boolean sharedTokenEnabled;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        AdminContext.Principal principal = resolve(request);
        if (principal == null) {
            return reject(response, HttpStatus.UNAUTHORIZED.value(), 401, "后台凭证无效或已过期，请重新登录");
        }
        AdminContext.set(principal);

        // ① 初始口令未修改：除改密相关接口外一律拒绝。
        //    放在服务端而不是只在前端弹窗，是因为前端提示只能拦住愿意配合的人。
        if (principal.mustChangePassword() && !isAuthPath(request)) {
            return reject(response, HttpStatus.FORBIDDEN.value(), 1024,
                    "首次登录必须先修改初始口令");
        }

        // ② 角色校验
        RequireRole required = requiredRole(handler);
        if (required != null && !principal.role().satisfies(required.value())) {
            return reject(response, HttpStatus.FORBIDDEN.value(), 1025,
                    "该操作需要" + required.value().getLabel() + "权限，当前身份是"
                            + (principal.role() == null ? "未知" : principal.role().getLabel()));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                               Object handler, Exception ex) {
        // ThreadLocal 必须清：容器线程会被复用，不清就会把上一个请求的身份带给下一个请求
        AdminContext.clear();
    }

    /**
     * 解析凭证。返回 {@code null} 表示认证失败。
     *
     * <p>账号令牌优先于共享令牌：本地拿共享令牌登着的同时又用账号登录时，
     * 应该以真实账号身份行事，否则留痕里全是"shared-token(dev)"，白搭了账号体系。
     */
    private AdminContext.Principal resolve(HttpServletRequest request) {
        String bearer = bearerToken(request);
        if (bearer != null) {
            Long adminId = JwtUtil.parseAdminId(bearer, jwtSecret);
            if (adminId != null) {
                try {
                    AdminUser u = adminAuthService.requireUsable(adminId);
                    return new AdminContext.Principal(
                            u.getId(), u.getUsername(), u.getDisplayName(),
                            AdminRole.parse(u.getRole()), false, u.mustChangePassword());
                } catch (BizException e) {
                    // 账号被停用 / 已删除：令牌本身有效，但身份不再可用
                    return null;
                }
            }
        }

        if (sharedTokenEnabled && matchesSharedToken(request.getHeader("X-Admin-Token"))) {
            // 共享令牌没有身份，role 给 MANAGER 是为了让本地联调不被权限挡住；
            // 日志与留痕里会以 shared-token(dev) 出现，一眼能看出这条记录没有可追溯的操作人。
            // mustChangePassword 恒为 false：没有账号，也就无所谓初始口令。
            return new AdminContext.Principal(null, "shared-token", "共享令牌(仅开发)",
                    AdminRole.MANAGER, true, false);
        }
        return null;
    }

    private String bearerToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String token = auth.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    /** 常量时间比较，消除"逐字节猜令牌"的时序侧信道。 */
    private boolean matchesSharedToken(String provided) {
        if (sharedToken == null || sharedToken.isBlank() || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                sharedToken.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isAuthPath(HttpServletRequest request) {
        return request.getRequestURI().startsWith(AUTH_PATH_PREFIX);
    }

    /** 方法上的注解优先于类上的；都没有则返回 {@code null}（登录即可）。 */
    private RequireRole requiredRole(Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return null;
        }
        RequireRole onMethod = method.getMethodAnnotation(RequireRole.class);
        if (onMethod != null) {
            return onMethod;
        }
        return method.getBeanType().getAnnotation(RequireRole.class);
    }

    private boolean reject(HttpServletResponse response, int httpStatus, int bizCode, String msg)
            throws java.io.IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + bizCode + ",\"msg\":\"" + msg + "\"}");
        return false;
    }
}
