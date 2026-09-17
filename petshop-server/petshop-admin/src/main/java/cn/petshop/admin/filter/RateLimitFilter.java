package cn.petshop.admin.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caffeine 滑动窗口限流：单实例内存计数（与本地缓存一致性约束一致）。
 *
 * 策略（对应审查 M-5）：写接口默认全部纳入限流，按「IP + 资源根路径」计数；
 * 敏感接口（登录 / 充值 / 核销 / 结算）使用更严格配额，并对核销连续失败做时间窗锁定，
 * 防止 6 位核销码被暴力枚举。GET 等只读请求不在此过滤（由缓存与分页上限约束）。
 *
 * <h3>客户端 IP 的来源（对应审查 P2-3）</h3>
 * 默认<b>只信 {@code remoteAddr}</b>：{@code X-Forwarded-For} 是客户端可以随便写的请求头，
 * 直连部署时信任它等于把限流开关交给攻击者 —— 每次请求换一个 XFF 就能绕开登录 / 充值的
 * 5 次/10s 配额与核销连续失败锁定。只有部署在<b>会剥离外部 XFF</b> 的可信网关 / 云托管之后，
 * 才通过 {@code PETSHOP_TRUST_FORWARDED_HEADERS=true} 显式打开。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = 10_000;
    /** 普通写接口：10s / 20 次（每 IP + 资源根） */
    private static final int WRITE_LIMIT = 20;
    /** 单 IP 全局写配额：10s / 100 次，防止换路径绕过单资源配额 */
    private static final int GLOBAL_WRITE_LIMIT = 100;
    /** 敏感写接口：10s / 5 次 */
    private static final int SENSITIVE_LIMIT = 5;

    /** 资源根路径 → 严格配额 */
    private static final Set<String> SENSITIVE_ROOTS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/recharges",
            "/api/v1/refunds",
            "/admin/verify",
            "/admin/settle",
            "/admin/refunds",
            // 后台登录：口令可被暴力猜解，纳入严格配额（10s / 5 次）。
            // 与 AdminAuthService 的按账号失败锁定叠加：IP 配额挡批量，账号锁挡定向撞库。
            "/admin/auth",
            // 商品自提核销：8 位码同样可被枚举，纳入严格配额
            "/admin/orders");

    /** 核销连续失败阈值与锁定时长 */
    private static final int VERIFY_MAX_FAILURES = 10;
    private static final Duration VERIFY_LOCK = Duration.ofMinutes(10);
    private static final String VERIFY_ROOT = "/admin/verify";

    private final Cache<String, Deque<Long>> counters = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofSeconds(60))
            .maximumSize(100_000)
            .build();

    /** IP → 连续核销失败次数，超过阈值锁定该 IP 一段时间 */
    private final Cache<String, AtomicInteger> verifyFailures = Caffeine.newBuilder()
            .expireAfterWrite(VERIFY_LOCK)
            .maximumSize(10_000)
            .build();

    /**
     * 是否信任 {@code X-Forwarded-For} / {@code X-Real-IP}。
     * 默认 false（见类注释）：只有可信网关会剥离外部 XFF 时才可以置 true。
     */
    private final boolean trustForwardedHeaders;

    public RateLimitFilter(
            @Value("${petshop.security.trust-forwarded-headers:false}") boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        boolean write = "POST".equals(method) || "PUT".equals(method)
                || "PATCH".equals(method) || "DELETE".equals(method);
        if (!write) {
            return true;
        }
        String uri = request.getRequestURI();
        return !(uri.startsWith("/api/") || uri.startsWith("/admin/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = clientIp(request);
        String root = resourceRoot(request.getRequestURI());

        if (VERIFY_ROOT.equals(root)) {
            AtomicInteger fails = verifyFailures.getIfPresent(ip);
            if (fails != null && fails.get() >= VERIFY_MAX_FAILURES) {
                reject(response, "核销失败次数过多，该终端已临时锁定，请 10 分钟后重试");
                return;
            }
        }

        int limit = SENSITIVE_ROOTS.contains(root) ? SENSITIVE_LIMIT : WRITE_LIMIT;
        boolean allowed = tryAcquire(ip + "|" + root, limit)
                && tryAcquire("*|" + ip, GLOBAL_WRITE_LIMIT);
        if (!allowed) {
            reject(response, "操作太频繁了，请稍后再试");
            return;
        }

        chain.doFilter(request, response);

        if (VERIFY_ROOT.equals(root)) {
            if (response.getStatus() == HttpStatus.OK.value()) {
                verifyFailures.invalidate(ip);
            } else {
                verifyFailures.get(ip, k -> new AtomicInteger()).incrementAndGet();
            }
        }
    }

    private boolean tryAcquire(String key, int limit) {
        Deque<Long> hits = counters.get(key, k -> new ArrayDeque<>());
        synchronized (hits) {
            long now = System.currentTimeMillis();
            while (!hits.isEmpty() && now - hits.peekFirst() >= WINDOW_MILLIS) {
                hits.pollFirst();
            }
            if (hits.size() >= limit) {
                return false;
            }
            hits.addLast(now);
            return true;
        }
    }

    /**
     * 取真实客户端 IP。
     *
     * <p>{@code X-Forwarded-For} / {@code X-Real-IP} 都是客户端可伪造的请求头，
     * 默认一律忽略、只信 {@code remoteAddr} —— 否则攻击者每次换一个 XFF 就能为每次尝试
     * 拿到一份新配额，限流与核销锁定形同虚设（对应审查 P2-3）。
     * 只有部署在会剥离外部 XFF 的可信网关之后，才由
     * {@code PETSHOP_TRUST_FORWARDED_HEADERS=true} 显式打开。</p>
     */
    private String clientIp(HttpServletRequest request) {
        if (!trustForwardedHeaders) {
            return request.getRemoteAddr();
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /** 把 /api/v1/recharges/RC123/mock-notify 归一为 /api/v1/recharges，使配额按资源而非按路径参数计数。 */
    private String resourceRoot(String uri) {
        if (uri.startsWith("/api/v1/")) {
            String[] seg = uri.split("/");
            return seg.length > 3 ? "/api/v1/" + seg[3] : "/api/v1";
        }
        if (uri.startsWith("/admin/")) {
            String[] seg = uri.split("/");
            return seg.length > 2 ? "/admin/" + seg[2] : "/admin";
        }
        return uri;
    }

    private void reject(HttpServletResponse response, String msg) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"msg\":\"" + msg + "\"}");
    }
}
