package cn.petshop.wallet.service;

import cn.petshop.common.audit.ConfigAuditLog;
import cn.petshop.common.audit.ConfigAuditService;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.wallet.entity.RechargeTier;
import cn.petshop.wallet.mapper.RechargeTierMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 充值档位：会员端定价 + 后台配置。
 *
 * <h3>这个类存在的原因（一个被修掉的洞）</h3>
 * 改造之前 {@code RechargeController.create} 直接收请求体里的 {@code gift}，
 * 只校验"赠送不超过本金"。也就是说赠送金额由<b>发起请求的一方</b>决定 ——
 * 前端可以送 1 元，也可以按本金上限送满；任何拿到会员令牌的人都能给自己造一次高赠送充值。
 * {@code recharge_tier} 表在库里躺着，却对实际入账金额没有约束力。
 *
 * <p>现在赠送金额的唯一出处是这张表：{@link #requireGift} 按本金精确匹配启用档位，
 * 匹配不到就拒绝下单。前端仍然可以把档位列表拿去做展示，但它的输入不再影响入账金额。</p>
 *
 * <h3>为什么必须精确匹配而不是"最近档位"</h3>
 * 送 300 和送 500 的赠送不同，若按最近档位取，用户充 499 会拿到 500 档的赠送；
 * 这类"少充一元多拿赠金"的规则一旦上线，会立刻被当成套利玩法传播。
 * 要么按档位充，要么拒绝 —— 明确比宽容便宜。
 */
@Service
@RequiredArgsConstructor
public class RechargeTierService {

    private final RechargeTierMapper tierMapper;
    private final ConfigAuditService auditService;

    // -------------------------------------------------------------- 会员端路径

    /** 启用档位列表，按本金升序。前端用它渲染充值选项。 */
    public List<RechargeTier> listEnabled(Long storeId) {
        return new ArrayList<>(tierMapper.selectList(new LambdaQueryWrapper<RechargeTier>()
                .eq(RechargeTier::getStoreId, storeId)
                .eq(RechargeTier::getEnabled, RechargeTier.ENABLED)
                .orderByAsc(RechargeTier::getAmount)));
    }

    /**
     * 按本金取赠送金额。匹配不到启用档位则拒绝下单。
     *
     * <p>金额比较用 {@code compareTo} 而不是 {@code equals}：
     * {@code BigDecimal("300.0")} 与 {@code BigDecimal("300.00")} 的 equals 为 false，
     * 用等值判断会让小程序传入的 {@code 300.0} 匹配失败，而错误现象是"某些金额充不了"，
     * 非常难定位。</p>
     */
    public BigDecimal requireGift(Long storeId, BigDecimal amount) {
        if (amount == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "充值金额不能为空");
        }
        RechargeTier matched = tierMapper.selectList(new LambdaQueryWrapper<RechargeTier>()
                        .eq(RechargeTier::getStoreId, storeId)
                        .eq(RechargeTier::getEnabled, RechargeTier.ENABLED))
                .stream()
                .filter(t -> t.getAmount() != null && t.getAmount().compareTo(amount) == 0)
                .findFirst()
                .orElse(null);
        if (matched == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID,
                    "该充值金额不在可选档位内，请选择门店提供的档位");
        }
        return matched.getGift() == null ? BigDecimal.ZERO : matched.getGift();
    }

    // -------------------------------------------------------------- 后台配置

    public List<RechargeTier> listAll(Long storeId) {
        return new ArrayList<>(tierMapper.selectList(new LambdaQueryWrapper<RechargeTier>()
                .eq(RechargeTier::getStoreId, storeId)
                .orderByAsc(RechargeTier::getAmount)));
    }

    @Data
    public static class TierCmd {
        private BigDecimal amount;
        private BigDecimal gift;
        private Integer enabled;
    }

    @Transactional
    public RechargeTier createTier(Long storeId, TierCmd cmd, String operator) {
        validate(cmd, true);
        assertAmountNotDuplicated(storeId, cmd.getAmount(), null);

        RechargeTier tier = new RechargeTier();
        tier.setStoreId(storeId);
        tier.setAmount(cmd.getAmount());
        tier.setGift(cmd.getGift() == null ? BigDecimal.ZERO : cmd.getGift());
        tier.setEnabled(cmd.getEnabled() == null ? RechargeTier.ENABLED
                : (cmd.getEnabled() == RechargeTier.ENABLED ? RechargeTier.ENABLED : RechargeTier.DISABLED));
        tierMapper.insert(tier);

        auditService.created(ConfigAuditLog.TARGET_RECHARGE_TIER, tier.getId(),
                "新增充值档位：充 " + plain(tier.getAmount()) + " 送 " + plain(tier.getGift()), operator);
        return tier;
    }

    @Transactional
    public RechargeTier updateTier(Long tierId, TierCmd cmd, String operator) {
        RechargeTier tier = requireTier(tierId);
        validate(cmd, false);
        if (cmd.getAmount() != null && cmd.getAmount().compareTo(tier.getAmount()) != 0) {
            assertAmountNotDuplicated(tier.getStoreId(), cmd.getAmount(), tierId);
        }

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("本金", tier.getAmount());
        before.put("赠送", tier.getGift());
        before.put("启用", enabledLabel(tier.getEnabled()));

        if (cmd.getAmount() != null) {
            tier.setAmount(cmd.getAmount());
        }
        if (cmd.getGift() != null) {
            tier.setGift(cmd.getGift());
        }
        if (cmd.getEnabled() != null) {
            tier.setEnabled(cmd.getEnabled() == RechargeTier.ENABLED
                    ? RechargeTier.ENABLED : RechargeTier.DISABLED);
        }
        // 改完再校一次"赠送不超过本金"：只改 gift 或只改 amount 都可能把组合改坏
        if (tier.getGift().compareTo(tier.getAmount()) > 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "赠送金额不得超过本金");
        }
        tierMapper.updateById(tier);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("本金", tier.getAmount());
        after.put("赠送", tier.getGift());
        after.put("启用", enabledLabel(tier.getEnabled()));
        auditService.diff(ConfigAuditLog.TARGET_RECHARGE_TIER, tierId, before, after, null, operator);
        return tier;
    }

    private void validate(TierCmd cmd, boolean create) {
        if (cmd == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "请求体不能为空");
        }
        if (create && cmd.getAmount() == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "充值本金不能为空");
        }
        if (cmd.getAmount() != null && cmd.getAmount().compareTo(new BigDecimal("0.01")) < 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "充值本金必须大于 0");
        }
        if (cmd.getGift() != null) {
            if (cmd.getGift().compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "赠送金额不能为负");
            }
            if (cmd.getAmount() != null && cmd.getGift().compareTo(cmd.getAmount()) > 0) {
                // 送得比本金还多没有商业解释，且会让"退款只退本金"这条口径失去意义
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "赠送金额不得超过本金");
            }
        }
        if (cmd.getEnabled() != null
                && cmd.getEnabled() != RechargeTier.ENABLED && cmd.getEnabled() != RechargeTier.DISABLED) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "启用标记只能是 1 或 0");
        }
    }

    /** 同一门店不允许两个启用档位本金相同：否则赠送金额取决于数据库返回顺序。 */
    private void assertAmountNotDuplicated(Long storeId, BigDecimal amount, Long excludeId) {
        if (amount == null) {
            return;
        }
        boolean dup = tierMapper.selectList(new LambdaQueryWrapper<RechargeTier>()
                        .eq(RechargeTier::getStoreId, storeId))
                .stream()
                .anyMatch(t -> !t.getId().equals(excludeId) && t.getAmount() != null
                        && t.getAmount().compareTo(amount) == 0);
        if (dup) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT,
                    "已存在本金为 " + plain(amount) + " 的档位，同一金额不能配两个赠送规则");
        }
    }

    private RechargeTier requireTier(Long tierId) {
        if (tierId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "档位 ID 不能为空");
        }
        RechargeTier tier = tierMapper.selectById(tierId);
        if (tier == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "充值档位不存在");
        }
        return tier;
    }

    private String enabledLabel(Integer enabled) {
        return enabled != null && enabled == RechargeTier.ENABLED ? "启用" : "停用";
    }

    private String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }
}
