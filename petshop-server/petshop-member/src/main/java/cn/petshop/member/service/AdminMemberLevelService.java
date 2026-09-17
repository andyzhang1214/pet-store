package cn.petshop.member.service;

import cn.petshop.common.audit.ConfigAuditLog;
import cn.petshop.common.audit.ConfigAuditService;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.member.entity.MemberLevel;
import cn.petshop.member.mapper.MemberLevelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台会员等级配置。
 *
 * <h3>当前生效范围（写清楚，免得高估）</h3>
 * 真正被读取的只有 {@code enabled} 与 {@code general_discount}：
 * {@link MemberLevelService#discountFor} 按会员的 {@code level} 查这一行，
 * 关闭或未配折扣时返回 {@code null}（折扣引擎跳过）。
 *
 * <p>{@code threshold} <b>目前没有生效</b> —— 会员等级由注册时固定为 1，
 * 全项目没有"消费累计升级"的写入路径（{@code member.total_paid} 同样无写入）。
 * 所以这个字段现在的作用是<b>记录门店打算怎么定级</b>，等技术侧补齐升级逻辑后即可直接生效。
 * 后台界面会把这个状态标出来，不写成"设置后自动升级"。</p>
 *
 * <h3>阈值必须严格递增</h3>
 * 一旦允许两个等级阈值相同或倒挂，等级判定就会出现"满足两个等级"的歧义，
 * 而歧义的解法通常取决于数据库返回顺序 —— 一个每天早上结果都不一样的系统。
 */
@Service
@RequiredArgsConstructor
public class AdminMemberLevelService {

    private final MemberLevelMapper levelMapper;
    private final ConfigAuditService auditService;

    public List<MemberLevel> listLevels(Long storeId) {
        return new ArrayList<>(levelMapper.selectList(new LambdaQueryWrapper<MemberLevel>()
                .eq(MemberLevel::getStoreId, storeId)
                .orderByAsc(MemberLevel::getLevel)));
    }

    @Data
    public static class LevelCmd {
        private String name;
        private BigDecimal threshold;
        /** 全场折扣，如 0.95；必须 (0,1] */
        private BigDecimal generalDiscount;
        /** 按服务分类的折扣，JSON 文本，如 {"GROOM":0.92} */
        private String serviceDiscount;
        /** 权益说明，JSON 文本 */
        private String benefits;
        private Integer enabled;
    }

    /**
     * 追加一个新等级。等级序号由服务端取「当前最大 + 1」，
     * 不允许客户端指定 —— {@code level} 是 {@code member.level} 的语义外键，
     * 由界面填数字意味着可能出现两个 level=3 的配置。
     *
     * <p><b>并发</b>（对应审查 P3-4）：「最大 + 1」这个算法本身不是原子的，
     * 两个店长同时点「新增等级」会算出同一个号，后到的 insert 撞 {@code uk_member_level}。
     * 所以撞键时重新取号重试，而不是把 500 抛给用户 —— 与
     * {@code AppointmentService#insertWithUniqueVerifyCode} 处理核销码碰撞是同一种做法。</p>
     */
    @Transactional
    public MemberLevel createLevel(Long storeId, LevelCmd cmd, String operator) {
        if (cmd == null || cmd.getName() == null || cmd.getName().isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "等级名称不能为空");
        }
        if (cmd.getThreshold() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "升级门槛不能为空（无门槛填 0）");
        }
        validateDiscount(cmd.getGeneralDiscount());

        MemberLevel level = new MemberLevel();
        level.setStoreId(storeId);
        level.setName(cmd.getName().trim());
        level.setThreshold(cmd.getThreshold());
        level.setGeneralDiscount(cmd.getGeneralDiscount());
        level.setServiceDiscount(blankToNull(cmd.getServiceDiscount()));
        level.setBenefits(blankToNull(cmd.getBenefits()));
        // 新建默认关闭：折扣是花钱的事，先配好再显式开启
        level.setEnabled(cmd.getEnabled() != null && cmd.getEnabled() == 1 ? 1 : 0);

        boolean inserted = false;
        for (int attempt = 0; attempt < 5 && !inserted; attempt++) {
            // 每一轮都重新读一遍现状：撞键说明别的店长刚加了一个等级，
            // 序号与门槛上限都得按新的现状重算，否则会插出一个门槛更低的等级。
            List<MemberLevel> existing = listLevels(storeId);
            int nextLevel = existing.stream()
                    .mapToInt(l -> l.getLevel() == null ? 0 : l.getLevel())
                    .max()
                    .orElse(0) + 1;
            BigDecimal prevThreshold = existing.stream()
                    .map(MemberLevel::getThreshold)
                    .filter(java.util.Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(null);
            if (prevThreshold != null && cmd.getThreshold().compareTo(prevThreshold) <= 0) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                        "新等级的门槛必须高于当前最高门槛（" + plain(prevThreshold) + "）");
            }
            level.setLevel(nextLevel);
            try {
                levelMapper.insert(level);
                inserted = true;
            } catch (DuplicateKeyException e) {
                // 号被并发抢走，下一轮重新取号
            }
        }
        if (!inserted) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "同时有其他人在新增等级，请稍后重试");
        }

        auditService.created(ConfigAuditLog.TARGET_MEMBER_LEVEL, level.getId(),
                "新增会员等级：" + level.getName() + "（等级 " + level.getLevel()
                        + "，门槛 " + plain(level.getThreshold()) + "）", operator);
        return level;
    }

    @Transactional
    public MemberLevel updateLevel(Long levelId, LevelCmd cmd, String operator) {
        MemberLevel level = requireLevel(levelId);
        if (cmd == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请求体不能为空");
        }
        validateDiscount(cmd.getGeneralDiscount());

        Map<String, Object> before = snapshot(level);

        if (cmd.getName() != null && !cmd.getName().isBlank()) {
            level.setName(cmd.getName().trim());
        }
        if (cmd.getThreshold() != null) {
            assertThresholdOrdered(level, cmd.getThreshold());
            level.setThreshold(cmd.getThreshold());
        }
        if (cmd.getGeneralDiscount() != null) {
            level.setGeneralDiscount(cmd.getGeneralDiscount());
        }
        if (cmd.getServiceDiscount() != null) {
            level.setServiceDiscount(blankToNull(cmd.getServiceDiscount()));
        }
        if (cmd.getBenefits() != null) {
            level.setBenefits(blankToNull(cmd.getBenefits()));
        }
        if (cmd.getEnabled() != null) {
            if (cmd.getEnabled() != 0 && cmd.getEnabled() != 1) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "启用标记只能是 1 或 0");
            }
            level.setEnabled(cmd.getEnabled());
        }
        levelMapper.updateById(level);

        auditService.diff(ConfigAuditLog.TARGET_MEMBER_LEVEL, levelId, before, snapshot(level), null, operator);
        return level;
    }

    /**
     * 门槛必须落在相邻两级的门槛之间。
     *
     * <p>只跟上一级比是不够的：把「银爪」的门槛改成 5000（超过「金爪」的 3000）时，
     * 只看上一级（1000）会通过校验，实际已与下一级倒挂。</p>
     */
    private void assertThresholdOrdered(MemberLevel level, BigDecimal threshold) {
        List<MemberLevel> all = listLevels(level.getStoreId());
        for (MemberLevel other : all) {
            if (other.getId().equals(level.getId())) {
                continue;
            }
            boolean isLower = other.getLevel() != null && level.getLevel() != null
                    && other.getLevel() < level.getLevel();
            if (other.getThreshold() == null) {
                continue;
            }
            if (isLower && threshold.compareTo(other.getThreshold()) <= 0) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                        "门槛必须高于低等级「" + other.getName() + "」的 " + plain(other.getThreshold()));
            }
            if (!isLower && threshold.compareTo(other.getThreshold()) >= 0) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                        "门槛必须低于高等级「" + other.getName() + "」的 " + plain(other.getThreshold()));
            }
        }
    }

    private void validateDiscount(BigDecimal discount) {
        if (discount == null) {
            return;
        }
        if (discount.compareTo(BigDecimal.ZERO) <= 0 || discount.compareTo(BigDecimal.ONE) > 0) {
            // 大于 1 是加价、小于等于 0 是白送，两者都不是"折扣"
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "全场折扣必须在 (0, 1] 之间，例如 0.95");
        }
    }

    private MemberLevel requireLevel(Long levelId) {
        if (levelId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "等级 ID 不能为空");
        }
        MemberLevel level = levelMapper.selectById(levelId);
        if (level == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "会员等级不存在");
        }
        return level;
    }

    private Map<String, Object> snapshot(MemberLevel level) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("等级", level.getLevel());
        m.put("名称", level.getName());
        m.put("升级门槛", level.getThreshold());
        m.put("全场折扣", level.getGeneralDiscount());
        m.put("按服务折扣", level.getServiceDiscount());
        m.put("权益", level.getBenefits());
        m.put("启用", level.getEnabled() != null && level.getEnabled() == 1 ? "启用" : "停用");
        return m;
    }

    private String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}
