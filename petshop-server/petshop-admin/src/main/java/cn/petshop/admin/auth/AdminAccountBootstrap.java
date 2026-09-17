package cn.petshop.admin.auth;

import cn.petshop.admin.auth.mapper.AdminUserMapper;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.web.BizException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * 初始店长账号引导。
 *
 * <h3>为什么不在 02_seed.sql 里插一行默认账号</h3>
 * 种子脚本会进版本库，等于把一个所有人都看得到的口令发给全世界；
 * 而且要换口令还得再发一次迁移脚本。改成配置驱动后，口令由部署者自己定、只留在环境变量里。
 *
 * <h3>只在一张空表上动手</h3>
 * 表里已有任何账号时直接跳过 —— 绝不做"重置初始口令"这种事。
 * 一个会在每次启动时重置管理员口令的引导逻辑，比没有引导逻辑危险得多。
 *
 * <h3>创建出来的账号强制改密</h3>
 * 无论口令是配置来的还是开发默认值，都标记 {@code must_change_pwd=1}：
 * 这个口令经过了配置文件、环境变量、可能还有同事的聊天窗口，不该被长期使用。
 * 服务端会在除改密接口外的所有请求上拦下它，不指望前端弹窗自觉。
 */
@Component
@RequiredArgsConstructor
public class AdminAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountBootstrap.class);

    private static final Set<String> DEV_PROFILES = Set.of("dev", "local", "test");
    /** 仅开发环境使用的默认口令。生产环境不会走到这里（见下方 fail fast） */
    private static final String DEV_DEFAULT_PASSWORD = "admin12345";
    private static final String DEV_DEFAULT_USERNAME = "manager";

    private final AdminAuthService adminAuthService;
    private final AdminUserMapper adminUserMapper;
    private final Environment env;

    @Value("${petshop.admin.bootstrap.username:}")
    private String bootstrapUsername;

    @Value("${petshop.admin.bootstrap.password:}")
    private String bootstrapPassword;

    @Value("${petshop.admin.bootstrap.display-name:}")
    private String bootstrapDisplayName;

    @Override
    public void run(ApplicationArguments args) {
        if (adminUserMapper.selectCount(null) > 0) {
            // 已经有账号了。这里不做任何事，尤其不重置口令。
            return;
        }

        boolean devLike = isDevLike();
        String username = bootstrapUsername == null || bootstrapUsername.isBlank()
                ? DEV_DEFAULT_USERNAME
                : bootstrapUsername.trim();

        String password = bootstrapPassword;
        boolean usingDevDefault = false;
        if (password == null || password.isBlank()) {
            if (!devLike) {
                // 生产环境既没有账号、又没给初始口令 —— 启动下去也是个谁都进不去的后台，
                // 不如现在就炸出来，把"忘了配"暴露在部署阶段而不是交付给门店之后。
                throw new IllegalStateException(
                        "后台一个账号都没有，且未配置初始店长口令。"
                                + "\n请设置环境变量 PETSHOP_ADMIN_BOOTSTRAP_USERNAME / PETSHOP_ADMIN_BOOTSTRAP_PASSWORD 后重启。"
                                + "\n（当前 profile=" + Arrays.toString(env.getActiveProfiles()) + "）");
            }
            password = DEV_DEFAULT_PASSWORD;
            usingDevDefault = true;
        }

        String displayName = bootstrapDisplayName == null || bootstrapDisplayName.isBlank()
                ? "店长" : bootstrapDisplayName.trim();

        try {
            AdminUser created = adminAuthService.createAccount(
                    username, displayName, AdminRole.MANAGER, password, 1L);
            log.warn("==== 已创建初始店长账号 ====");
            log.warn("登录名：{}", created.getUsername());
            if (usingDevDefault) {
                // 开发默认口令是公开知识，写进日志不构成额外泄漏，反而省去到处翻文档
                log.warn("初始口令：{}（开发默认值）", DEV_DEFAULT_PASSWORD);
            } else {
                log.warn("初始口令：来自配置项 petshop.admin.bootstrap.password（不在此打印）");
            }
            log.warn("该账号被标记为「需修改初始口令」，首次登录必须先改密才能做其他操作。");
            log.warn("===========================");
        } catch (BizException e) {
            // 口令强度不达标等：直接失败退出，别留一个进不去的后台
            throw new IllegalStateException("初始店长账号创建失败：" + e.getMessage(), e);
        }
    }

    private boolean isDevLike() {
        String[] profiles = env.getActiveProfiles();
        return profiles.length == 0 || Arrays.stream(profiles).anyMatch(DEV_PROFILES::contains);
    }
}
