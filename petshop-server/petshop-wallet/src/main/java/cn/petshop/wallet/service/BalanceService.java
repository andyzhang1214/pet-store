package cn.petshop.wallet.service;

import cn.petshop.common.enums.LedgerBizType;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.wallet.entity.BalanceLedger;
import cn.petshop.wallet.entity.MemberAccount;
import cn.petshop.wallet.mapper.BalanceLedgerMapper;
import cn.petshop.wallet.mapper.MemberAccountMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class BalanceService {

    public static final BigDecimal ZERO = BigDecimal.ZERO;

    private final MemberAccountMapper accountMapper;
    private final BalanceLedgerMapper ledgerMapper;

    @Transactional
    public void freeze(Long memberId, BigDecimal amt, String bizNo) {
        requirePositive(amt);
        if (accountMapper.freeze(memberId, amt) == 0) {
            throw new BizException(ErrorCode.BIZ_BALANCE_NOT_ENOUGH);
        }
        MemberAccount acc = accountMapper.selectById(memberId);
        ledgerMapper.insert(BalanceLedger.of(memberId, LedgerBizType.FREEZE, ZERO, ZERO,
                acc.getBalance(), acc.getFrozenAmount(), bizNo));
    }

    @Transactional
    public void unfreeze(Long memberId, BigDecimal amt, String bizNo) {
        requirePositive(amt);
        if (accountMapper.unfreeze(memberId, amt) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "冻结额不足");
        }
        MemberAccount acc = accountMapper.selectById(memberId);
        ledgerMapper.insert(BalanceLedger.of(memberId, LedgerBizType.UNFREEZE, ZERO, ZERO,
                acc.getBalance(), acc.getFrozenAmount(), bizNo));
    }

    /**
     * 结算抵扣：先冲回冻结 F，再按分摊比例扣减实际金额 A（A 可小于或大于 F，差额自动从可用余额补足/退回）。
     */
    @Transactional
    public void consumeFrozen(Long memberId, BigDecimal frozenAmt, BigDecimal actualAmt, String bizNo) {
        requirePositive(frozenAmt);
        if (actualAmt.compareTo(ZERO) < 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "结算金额不能为负");
        }
        if (accountMapper.unfreeze(memberId, frozenAmt) == 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "冻结额不足");
        }
        MemberAccount acc = accountMapper.selectById(memberId);
        ledgerMapper.insert(BalanceLedger.of(memberId, LedgerBizType.UNFREEZE, ZERO, ZERO,
                acc.getBalance(), acc.getFrozenAmount(), bizNo));
        consumeAvailable(memberId, actualAmt, bizNo);
    }

    @Transactional
    public void consumeAvailable(Long memberId, BigDecimal amt, String bizNo) {
        consumeAvailableWithSplit(memberId, amt, bizNo);
    }

    /**
     * 余额支付并返回实际拆分明细 {@code [本金, 赠送金]}，供业务侧落库。
     *
     * <p>存在的理由：商品订单要把「这笔单从本金里扣了多少、从赠送金里扣了多少」记在
     * {@code goods_order.principal_paid / gift_paid} 上，退款时才能按<b>原比例</b>原样退回。
     * 拆分口径与本方法写入的账本流水完全一致（同一个 {@code splitByRatio} 结果），
     * 所以「订单金额 == 账本流水金额」这条对账恒等式从订单诞生就成立。
     *
     * <p>若让业务侧自己按余额比例重算，两次充值赠送比例不同时结果会与账本不符，
     * 退款金额随之算错 —— 这正是退款公式上一轮踩过的坑。
     *
     * @return {@code [principal, gift]}，两者之和恒等于 {@code amt}；{@code amt <= 0} 时返回 {@code [0, 0]}
     */
    @Transactional
    public BigDecimal[] consumeAvailableWithSplit(Long memberId, BigDecimal amt, String bizNo) {
        if (amt.compareTo(ZERO) <= 0) {
            return new BigDecimal[]{ZERO, ZERO};
        }
        MemberAccount before = accountMapper.selectById(memberId);
        if (accountMapper.deductAvailable(memberId, amt) == 0) {
            throw new BizException(ErrorCode.BIZ_BALANCE_NOT_ENOUGH);
        }
        MemberAccount after = accountMapper.selectById(memberId);
        BigDecimal[] split = splitByRatio(before, amt);
        ledgerMapper.insert(BalanceLedger.of(memberId, LedgerBizType.CONSUME, split[0].negate(), split[1].negate(),
                after.getBalance(), after.getFrozenAmount(), bizNo));
        return split;
    }

    @Transactional
    public void recharge(Long memberId, BigDecimal principal, BigDecimal gift, String bizNo) {
        if (principal.compareTo(ZERO) <= 0 && gift.compareTo(ZERO) <= 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "充值金额非法");
        }
        accountMapper.recharge(memberId, principal, gift);
        MemberAccount acc = accountMapper.selectById(memberId);
        ledgerMapper.insert(BalanceLedger.of(memberId, LedgerBizType.RECHARGE, principal, gift,
                acc.getBalance(), acc.getFrozenAmount(), bizNo));
    }

    /**
     * 余额出账（钱离开账户体系，对外退款）。
     *
     * ⚠️ 当前产品口径为「退款只入余额」，本方法<b>没有生产调用方</b>，仅为将来开通微信原路退款预留；
     * 反向动作见 {@link #refundToBalance}。保留它的另一个作用是把"退款不得把余额扣成负数"这条
     * 约束固定在回归测试里（SecurityRegressionTest ③）。
     */
    @Transactional
    public void refund(Long memberId, BigDecimal principal, BigDecimal gift, String bizNo, String operator, String remark) {
        requirePositive(principal.add(gift));
        BigDecimal pAbs = principal.abs();
        BigDecimal gAbs = gift.abs();
        BigDecimal refAmt = pAbs.add(gAbs);
        // 条件更新：余额不足直接拒绝，杜绝对超额退款写出负余额账户
        if (accountMapper.refund(memberId, refAmt, pAbs, gAbs) == 0) {
            throw new BizException(ErrorCode.BIZ_BALANCE_NOT_ENOUGH, "退款金额超过会员可用余额");
        }
        MemberAccount acc = accountMapper.selectById(memberId);
        BalanceLedger l = BalanceLedger.of(memberId, LedgerBizType.REFUND, principal.negate(), gift.negate(),
                acc.getBalance(), acc.getFrozenAmount(), bizNo);
        l.setOperator(operator);
        l.setRemark(remark);
        ledgerMapper.insert(l);
    }

    /**
     * 退款入账：把退款金额退回会员可用余额 —— 当前唯一启用的退款出口。
     *
     * 两条不变量：
     * <ol>
     *   <li><b>幂等</b>：同一退款单号（bizNo）只允许入账一次。主防线是 refund_apply 的状态 CAS，
     *       这里按 flow 再查一次同号 REFUND_IN 流水做兜底，防止"CAS 被绕过 / 被手工重放"造成重复加钱。</li>
     *   <li><b>余额恒等</b>：账本记录 +principal/+gift，因此
     *       {@code member.balance == Σ(principalAmt + giftAmt)} 依然成立，对账可机检。</li>
     * </ol>
     *
     * 拆分规则：默认全额计本金（principal）。退款回账户的是顾客自己的钱，计成赠送金会让后续
     * 消费分摊与「可退本金」显示一起失真；有来源消费流水的场景由调用方按原比例传入。
     */
    @Transactional
    public void refundToBalance(Long memberId, BigDecimal principal, BigDecimal gift,
                                String bizNo, String operator, String remark) {
        if (principal == null || gift == null || principal.compareTo(ZERO) < 0 || gift.compareTo(ZERO) < 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "退款金额不能为负");
        }
        BigDecimal amt = principal.add(gift);
        requirePositive(amt);
        if (bizNo == null || bizNo.isBlank()) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "退款单号不能为空");
        }
        Long duplicated = ledgerMapper.selectCount(new LambdaQueryWrapper<BalanceLedger>()
                .eq(BalanceLedger::getBizNo, bizNo)
                .eq(BalanceLedger::getBizType, LedgerBizType.REFUND_IN));
        if (duplicated != null && duplicated > 0) {
            throw new BizException(ErrorCode.BIZ_STATE_CONFLICT, "该退款单已入账，请勿重复提交");
        }
        if (accountMapper.refundIn(memberId, amt) == 0) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "会员账户不存在");
        }
        MemberAccount acc = accountMapper.selectById(memberId);
        BalanceLedger l = BalanceLedger.of(memberId, LedgerBizType.REFUND_IN, principal, gift,
                acc.getBalance(), acc.getFrozenAmount(), bizNo);
        l.setOperator(operator);
        l.setRemark(remark);
        ledgerMapper.insert(l);
    }

    public WalletVO wallet(Long memberId) {
        MemberAccount acc = accountMapper.selectById(memberId);
        WalletVO vo = new WalletVO();
        if (acc == null) {
            vo.setBalance(ZERO);
            vo.setFrozenAmount(ZERO);
            vo.setAvailable(ZERO);
            vo.setRefundablePrincipal(ZERO);
            return vo;
        }
        vo.setBalance(acc.getBalance());
        vo.setFrozenAmount(acc.getFrozenAmount());
        vo.setAvailable(acc.getBalance().subtract(acc.getFrozenAmount()));
        vo.setRefundablePrincipal(refundablePrincipal(acc));
        return vo;
    }

    public static BigDecimal refundablePrincipal(MemberAccount acc) {
        BigDecimal cum = acc.getCumPrincipal().add(acc.getCumGift());
        if (cum.compareTo(ZERO) <= 0) {
            return acc.getBalance();
        }
        return acc.getBalance().multiply(acc.getCumPrincipal())
                .divide(cum, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal[] splitByRatio(MemberAccount acc, BigDecimal amt) {
        BigDecimal cum = acc.getCumPrincipal().add(acc.getCumGift());
        if (cum.compareTo(ZERO) <= 0) {
            return new BigDecimal[]{amt, ZERO};
        }
        BigDecimal principal = amt.multiply(acc.getCumPrincipal())
                .divide(cum, 2, RoundingMode.HALF_UP);
        return new BigDecimal[]{principal, amt.subtract(principal)};
    }

    private void requirePositive(BigDecimal amt) {
        if (amt == null || amt.compareTo(ZERO) <= 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "金额非法");
        }
    }
}
