package cn.petshop.common.context;

import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;

/**
 * 后台操作人上下文。
 *
 * <p>由 {@code AdminTokenInterceptor} 在请求进入时填入、请求结束时清除。
 *
 * <h3>它解决的是什么问题</h3>
 * 改造之前，后台只有一枚部署时配置的共享令牌，请求里根本没有"谁在操作"的信息 ——
 * 于是退款单的审核人只能由前端在请求体里自报（{@code operator} 字段）。
 * 那不是留痕，那是「让操作者自己声明自己是谁」：任何拿到令牌的人都能把审核人写成别人的名字，
 * 出了资金纠纷这份记录一分钱证据价值都没有。
 * 现在操作人由服务端从令牌解析出的账号读取，请求体里<b>没有</b>这个字段可填。
 */
public final class AdminContext {

    /** 留痕字段在库里是 VARCHAR(32)，超长会被截断甚至报错，统一在这里收敛 */
    private static final int OPERATOR_MAX_LEN = 32;

    /**
     * 当前操作人。
     *
     * @param id                  admin_user 主键；共享令牌模式下为 null
     * @param username            登录名
     * @param displayName         显示名（留痕用）
     * @param role                角色；理论上不为 null，未识别时由 {@code AdminRole.parse} 返回 null
     * @param sharedToken         是否来自开发用的共享令牌，而不是真实账号
     * @param mustChangePassword  初始口令未修改，除改密接口外一律拒绝访问
     */
    public record Principal(Long id, String username, String displayName,
                            AdminRole role, boolean sharedToken, boolean mustChangePassword) {

        /**
         * 写进流水 / 审核意见的操作人标识。
         *
         * <p>共享令牌模式显式落成 {@code shared-token(dev)} 而不是某个像名字的东西：
         * 看报表的人一眼就知道这条记录<b>没有可追溯的操作人</b>，
         * 不会误以为"是某个叫张三的员工干的"。生产环境不允许共享令牌登录，
         * 所以这种记录只会出现在本地联调数据里。
         */
        public String operatorName() {
            String name;
            if (sharedToken) {
                name = "shared-token(dev)";
            } else if (displayName != null && !displayName.isBlank()) {
                name = displayName;
            } else if (username != null && !username.isBlank()) {
                name = username;
            } else {
                name = "unknown";
            }
            return name.length() > OPERATOR_MAX_LEN ? name.substring(0, OPERATOR_MAX_LEN) : name;
        }
    }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private AdminContext() {
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal get() {
        return HOLDER.get();
    }

    /** 取当前操作人，未登录直接拒绝。 */
    public static Principal require() {
        Principal p = HOLDER.get();
        if (p == null) {
            throw new BizException(ErrorCode.BIZ_PERMISSION_DENIED, "后台身份缺失，请重新登录");
        }
        return p;
    }

    /**
     * 当前操作人的留痕名。
     *
     * <p>服务层需要操作人时统一走这里，而不是把它做成方法入参：
     * 做成入参意味着调用方总能传别的值进去，而"谁能指定审核人"正是这次要堵的口子。
     * 唯一的例外是测试 —— 测试没有 HTTP 上下文，因此服务层仍保留了显式传入操作人的重载。
     */
    public static String operatorName() {
        return require().operatorName();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
