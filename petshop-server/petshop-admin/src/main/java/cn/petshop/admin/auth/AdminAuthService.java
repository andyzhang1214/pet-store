package cn.petshop.admin.auth;

import cn.petshop.admin.auth.mapper.AdminUserMapper;
import cn.petshop.common.enums.AdminRole;
import cn.petshop.common.util.PasswordUtil;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 后台账号：登录、取当前账号、改口令、账号维护。
 *
 * <h3>每次请求都回库读账号，不做缓存</h3>
 * 角色与账号状态若缓存在内存或令牌里，"停用某人"、"把店长降成店员"就要等缓存过期或令牌失效才生效 ——
 * 而这两件事恰恰是出事时最需要立刻生效的。后台请求量以"人工点几下"为单位，
 * 一次主键查询的代价远低于权限撤销延迟带来的风险。这里刻意不做缓存。
 *
 * <h3>登录失败锁定按账号计数</h3>
 * 除了网关侧的 IP 限流（10s / 5 次），再按账号累计失败次数：只按 IP 挡不住分布式撞库，
 * 而只按账号锁又给了别人"知道用户名就能锁死你"的骚扰手段。
 * 两者叠加后，撞库成本被抬高到不划算，而正常用户偶尔输错一两次不受影响。
 */
@Service
@RequiredArgsConstructor
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    /** 连续失败几次后锁定账号 */
    private static final int MAX_LOGIN_FAILURES = 5;
    /** 锁定时长；同时也是失败计数的过期时间（长时间不尝试就自然清零） */
    private static final Duration LOGIN_LOCK = Duration.ofMinutes(10);

    private final AdminUserMapper adminUserMapper;

    /**
     * 账号不存在时用来做一次等价哈希的哑值。
     *
     * <p>不做这一步的话，"账号不存在"会直接返回、而"口令错误"要跑一遍 PBKDF2，
     * 两者耗时差两个数量级 —— 拿秒表就能批量探测出哪些用户名真实存在。
     * 拿哑值跑一遍，把两条分支的耗时拉到同一量级。
     */
    private static final String DUMMY_HASH = PasswordUtil.hash("dummy-password-for-timing-equalization");

    private final Cache<String, AtomicInteger> loginFailures = Caffeine.newBuilder()
            .expireAfterWrite(LOGIN_LOCK)
            .maximumSize(10_000)
            .build();

    // ==================== 登录 ====================

    /**
     * 校验账号口令。成功返回账号实体，失败抛业务异常。
     *
     * <p>三种失败（账号不存在 / 口令错误 / 账号停用）里，前两种对外返回<b>同一个</b>提示，
     * 不告诉调用方"用户名是存在的、只是密码不对" —— 那等于免费提供用户名枚举。
     * 停用状态则单独提示：账号被停用的人需要知道去找店长，藏起来只会让人反复试。
     */
    @Transactional
    public AdminUser login(String username, String rawPassword) {
        String uname = username == null ? "" : username.trim();
        if (uname.isEmpty() || rawPassword == null || rawPassword.isEmpty()) {
            throw new BizException(ErrorCode.BIZ_ADMIN_CREDENTIAL_INVALID);
        }
        if (locked(uname)) {
            throw new BizException(ErrorCode.BIZ_ADMIN_LOGIN_LOCKED, "连续失败过多，请 10 分钟后再试");
        }

        AdminUser user = findByUsername(uname);
        if (user == null) {
            // 仍然跑一遍哈希，抹平"账号是否存在"的时间差
            PasswordUtil.verify(rawPassword, DUMMY_HASH);
            recordFailure(uname);
            throw new BizException(ErrorCode.BIZ_ADMIN_CREDENTIAL_INVALID);
        }
        if (!PasswordUtil.verify(rawPassword, user.getPasswordHash())) {
            recordFailure(uname);
            throw new BizException(ErrorCode.BIZ_ADMIN_CREDENTIAL_INVALID);
        }
        if (!user.isEnabled()) {
            throw new BizException(ErrorCode.BIZ_ADMIN_ACCOUNT_DISABLED);
        }

        loginFailures.invalidate(uname);
        LocalDateTime now = LocalDateTime.now();
        AdminUser patch = new AdminUser();
        patch.setId(user.getId());
        patch.setLastLoginAt(now);
        adminUserMapper.updateById(patch);
        user.setLastLoginAt(now);
        return user;
    }

    /**
     * 取当前账号，供鉴权拦截器每次请求调用。
     *
     * <p>账号被删或被停用时这里直接拒绝，令牌即使还没过期也会立刻失效 ——
     * 这就是不做令牌吊销名单的底气。
     */
    public AdminUser requireUsable(Long adminId) {
        AdminUser user = adminId == null ? null : adminUserMapper.selectById(adminId);
        if (user == null || !user.isEnabled()) {
            throw new BizException(ErrorCode.BIZ_ADMIN_ACCOUNT_DISABLED,
                    user == null ? "账号不存在或已删除，请重新登录" : "账号已停用，请联系店长");
        }
        return user;
    }

    public AdminUser findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, username.trim()));
    }

    // ==================== 口令修改 ====================

    /**
     * 本人改口令。必须校验原口令 —— 否则一个被借用的已登录浏览器就能永久接管账号。
     */
    @Transactional
    public void changePassword(Long adminId, String oldPassword, String newPassword) {
        AdminUser user = adminUserMapper.selectById(adminId);
        if (user == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "账号不存在");
        }
        if (!PasswordUtil.verify(oldPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.BIZ_ADMIN_CREDENTIAL_INVALID, "原口令不正确");
        }
        applyNewPassword(user, newPassword);
        log.info("后台账号 {} 修改了口令", user.getUsername());
    }

    /**
     * 店长重置他人口令，无需原口令。重置后强制其下次登录修改。
     *
     * <p>这里<b>不能</b>复用 {@link #changePassword}：店长不可能知道店员的原口令，
     * 强行要求只会逼出"把口令写在群里互相同步"这种更糟的做法。
     */
    @Transactional
    public void resetPassword(Long targetId, String newPassword) {
        AdminUser user = adminUserMapper.selectById(targetId);
        if (user == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "账号不存在");
        }
        applyNewPassword(user, newPassword);
        AdminUser patch = new AdminUser();
        patch.setId(user.getId());
        patch.setMustChangePwd(true);
        adminUserMapper.updateById(patch);
        log.info("后台账号 {} 的口令被重置，已标记需下次登录修改", user.getUsername());
    }

    private void applyNewPassword(AdminUser user, String newPassword) {
        try {
            PasswordUtil.requireStrength(newPassword);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, e.getMessage());
        }
        if (PasswordUtil.verify(newPassword, user.getPasswordHash())) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "新口令不能与原口令相同");
        }
        AdminUser patch = new AdminUser();
        patch.setId(user.getId());
        patch.setPasswordHash(PasswordUtil.hash(newPassword));
        patch.setMustChangePwd(false);
        patch.setUpdatedAt(LocalDateTime.now());
        adminUserMapper.updateById(patch);
    }

    // ==================== 账号维护 ====================

    public List<AdminUser> listAll() {
        return adminUserMapper.selectList(new LambdaQueryWrapper<AdminUser>()
                .orderByAsc(AdminUser::getId));
    }

    @Transactional
    public AdminUser createAccount(String username, String displayName, AdminRole role,
                                   String initialPassword, Long storeId) {
        String uname = username == null ? "" : username.trim();
        if (!uname.matches("^[A-Za-z0-9_.-]{3,32}$")) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                    "登录名只能包含字母、数字、下划线、点、连字符，长度 3-32");
        }
        if (role == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请选择角色");
        }
        String name = displayName == null || displayName.isBlank() ? uname : displayName.trim();
        if (name.length() > 32) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "显示名不能超过 32 个字");
        }
        if (findByUsername(uname) != null) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "登录名已存在");
        }
        try {
            PasswordUtil.requireStrength(initialPassword);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, e.getMessage());
        }

        LocalDateTime now = LocalDateTime.now();
        AdminUser u = new AdminUser();
        u.setStoreId(storeId == null ? 1L : storeId);
        u.setUsername(uname);
        u.setDisplayName(name);
        u.setPasswordHash(PasswordUtil.hash(initialPassword));
        u.setRole(role.name());
        u.setStatus(AdminUser.STATUS_ENABLED);
        // 由别人代设的初始口令，必须本人改过一次才算数
        u.setMustChangePwd(true);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        adminUserMapper.insert(u);
        return u;
    }

    /**
     * 改显示名 / 角色 / 启用状态。口令不走这里。
     *
     * <p>两条自锁保护：不能把自己的角色降成店员（会把自己关在门外，且没有别的入口能改回来），
     * 不能停用自己。这类"把自己锁死"的操作在单店后台里没有救援路径，只能直接拦掉。
     */
    @Transactional
    public AdminUser updateAccount(Long operatorId, Long targetId, String displayName,
                                   AdminRole role, Boolean enabled) {
        AdminUser target = adminUserMapper.selectById(targetId);
        if (target == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "账号不存在");
        }
        boolean self = target.getId().equals(operatorId);

        AdminUser patch = new AdminUser();
        patch.setId(target.getId());
        boolean changed = false;

        if (displayName != null && !displayName.isBlank()) {
            if (displayName.trim().length() > 32) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "显示名不能超过 32 个字");
            }
            patch.setDisplayName(displayName.trim());
            changed = true;
        }
        if (role != null) {
            if (self && role != AdminRole.MANAGER) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "不能把自己降为店员，请让另一位店长操作");
            }
            patch.setRole(role.name());
            changed = true;
        }
        if (enabled != null) {
            if (self && !enabled) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "不能停用自己的账号");
            }
            if (!enabled && target.isEnabled() && wouldRemoveLastManager(target)) {
                throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "这是最后一个启用的店长账号，不能停用");
            }
            patch.setStatus(enabled ? AdminUser.STATUS_ENABLED : AdminUser.STATUS_DISABLED);
            changed = true;
        }
        if (!changed) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "没有需要修改的内容");
        }
        patch.setUpdatedAt(LocalDateTime.now());
        adminUserMapper.updateById(patch);
        return adminUserMapper.selectById(target.getId());
    }

    /**
     * 判断停用这个账号是否会让系统里一个可用的店长都不剩。
     *
     * <p>全店只剩一个店长、又把它停掉，后果是没人能审退款、没人能改价目，而且没有任何界面
     * 能把权限改回来 —— 只能进数据库手改。单店后台没有"超级管理员"兜底，所以这条必须在代码里挡。
     */
    private boolean wouldRemoveLastManager(AdminUser target) {
        if (!AdminRole.MANAGER.name().equals(target.getRole())) {
            return false;
        }
        long enabledManagers = adminUserMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getRole, AdminRole.MANAGER.name())
                .eq(AdminUser::getStatus, AdminUser.STATUS_ENABLED));
        return enabledManagers <= 1;
    }

    // ==================== 失败计数 ====================

    private boolean locked(String username) {
        AtomicInteger f = loginFailures.getIfPresent(username);
        return f != null && f.get() >= MAX_LOGIN_FAILURES;
    }

    private void recordFailure(String username) {
        int n = loginFailures.get(username, k -> new AtomicInteger()).incrementAndGet();
        if (n == MAX_LOGIN_FAILURES) {
            log.warn("后台账号 {} 连续登录失败 {} 次，已临时锁定 {} 分钟", username, n, LOGIN_LOCK.toMinutes());
        }
    }
}
