package cn.petshop.admin.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 启动安全守卫（对应审查 C-2，并在引入后台账号体系后收紧了一条）。
 *
 * <h3>守则（fail-closed）</h3>
 * 只有<b>显式</b>声明 dev / local / test profile 才按开发态放行（仅告警）；
 * 其余一切情况——包括<b>未设置任何 profile</b>——都按生产标准校验，
 * 检测到任一"开发期才允许存在"的配置 → 直接拒绝启动。
 * 宁可让部署脚本报错，也不要静默上线一个任何人都能用公开默认值进去的后台。
 *
 * <h3>为什么空 profile 也按生产算</h3>
 * 配套的 {@code application.yml} 已把默认 profile 从 dev 改为 prod，
 * 但若有人用 {@code --spring.profiles.active=} 显式置空、或剔除 application.yml 启动，
 * 空 profile 会落到这里。历史上"空 = dev-like"正是漏配环境变量即裸奔的根因，不再放行。
 *
 * <h3>共享令牌由「必须配」改为「禁止存在」</h3>
 * 共享令牌没有身份，谁拿到谁是店长，退款的审核人只能由前端自报，留痕没有审计效力。
 * 账号体系落地后生产环境不该再留这条路径：
 * 生产环境<b>必须</b>把 {@code petshop.admin.shared-token-enabled} 关掉，且不应再配置
 * {@code petshop.admin.token}。
 */
@Component
@RequiredArgsConstructor
public class StartupSecurityGuard {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityGuard.class);

    private static final Set<String> DEV_PROFILES = Set.of("dev", "local", "test");

    private static final String DEV_JWT_SECRET = "petshop-dev-secret-change-me-in-production";
    private static final String DEV_ADMIN_TOKEN = "dev-admin-token";

    private final Environment env;

    @Value("${petshop.jwt.secret:}")
    private String jwtSecret;

    @Value("${petshop.admin.token:}")
    private String adminToken;

    @Value("${petshop.admin.shared-token-enabled:true}")
    private boolean sharedTokenEnabled;

    @Value("${petshop.wx.mock:false}")
    private boolean wxMock;

    @PostConstruct
    public void verify() {
        String[] profiles = env.getActiveProfiles();
        // 注意：profiles 为空 = 没有显式选择开发态 → 按生产校验（fail-closed），
        // 不再把空当成 dev-like 放行（见类注释）。
        boolean devLike = Arrays.stream(profiles).anyMatch(DEV_PROFILES::contains);

        boolean jwtDefault = jwtSecret == null || jwtSecret.isBlank() || DEV_JWT_SECRET.equals(jwtSecret);

        if (devLike) {
            if (jwtDefault || sharedTokenEnabled || wxMock) {
                log.warn("==== 开发模式安全提醒 ====");
                log.warn("当前 profile={}，正在使用开发期配置（JWT 密钥为默认值={}，共享令牌登录={}，wx.mock={}）。",
                        Arrays.toString(profiles), jwtDefault, sharedTokenEnabled, wxMock);
                log.warn("生产环境必须：注入强随机 PETSHOP_JWT_SECRET、"
                        + "设 PETSHOP_ADMIN_SHARED_TOKEN_ENABLED=false、设 PETSHOP_WX_MOCK=false。");
                log.warn("===========================");
            }
            return;
        }

        List<String> problems = new ArrayList<>();
        if (jwtDefault) {
            problems.add("petshop.jwt.secret 仍为默认值或为空，必须通过 PETSHOP_JWT_SECRET 注入强随机密钥"
                    + "（否则任何人可伪造会员或后台令牌）");
        }
        if (sharedTokenEnabled) {
            problems.add("petshop.admin.shared-token-enabled 仍为 true。"
                    + "共享令牌没有操作人身份，生产环境必须设 PETSHOP_ADMIN_SHARED_TOKEN_ENABLED=false，"
                    + "改用账号登录后台");
        }
        if (adminToken != null && !adminToken.isBlank()) {
            String hint = DEV_ADMIN_TOKEN.equals(adminToken) ? "（且是公开的开发默认值）" : "";
            problems.add("petshop.admin.token 已配置" + hint + "，生产环境不应存在共享令牌，"
                    + "请清空 PETSHOP_ADMIN_TOKEN");
        }
        if (wxMock) {
            problems.add("petshop.wx.mock=true，该模式允许伪造 openid 登录与模拟支付回调，生产必须为 false");
        }

        if (!problems.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "生产环境安全检查未通过，拒绝启动（profile=" + Arrays.toString(profiles) + "）：");
            for (String p : problems) {
                sb.append("\n - ").append(p);
            }
            sb.append("\n如为误判，请显式设置 SPRING_PROFILES_ACTIVE=dev 并自行承担风险。");
            throw new IllegalStateException(sb.toString());
        }
        log.info("生产环境安全检查通过（profile={}）：账号登录模式，无共享令牌，微信非 mock",
                Arrays.toString(profiles));
    }
}
