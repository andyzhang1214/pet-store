package cn.petshop.wallet.service;

import cn.petshop.common.util.NoUtil;
import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import cn.petshop.wallet.entity.PayOrder;
import cn.petshop.wallet.mapper.PayOrderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PayService {

    private final PayOrderMapper payOrderMapper;
    private final BalanceService balanceService;

    @Transactional
    public PayOrder createRechargeOrder(Long memberId, Long storeId, BigDecimal amount, String channel) {
        if (memberId == null) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "memberId 不能为空");
        }
        if (amount == null || amount.compareTo(new BigDecimal("0.01")) < 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "充值金额非法");
        }
        PayOrder order = new PayOrder();
        order.setPayNo(NoUtil.payNo());
        order.setStoreId(storeId);
        order.setMemberId(memberId);
        order.setBizType(PayOrder.BIZ_RECHARGE);
        order.setBizNo("RC" + order.getPayNo().substring(2));
        order.setAmount(amount);
        order.setChannel(channel);
        order.setStatus(PayOrder.STATUS_CREATED);
        order.setNotifyCount(0);
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        payOrderMapper.insert(order);
        return order;
    }

    /**
     * 支付回调入账：CAS CREATED→SUCCESS 保证幂等，充值入账与状态更新同事务。
     * 安全约束：
     *  1. operatorMemberId 非空时校验订单归属（防越权触发他人订单入账）；
     *  2. principal + gift 必须与订单金额完全对账（防客户端任意指定入账金额）；
     *     生产微信回调时传 operatorMemberId = null，金额取自验签后的回包。
     */
    @Transactional
    public boolean onPaySuccess(String payNo, Long operatorMemberId, String wxTransactionId, BigDecimal principal, BigDecimal gift) {
        PayOrder order = payOrderMapper.selectOne(new LambdaQueryWrapper<PayOrder>()
                .eq(PayOrder::getPayNo, payNo));
        if (order == null) {
            throw new BizException(ErrorCode.BIZ_NOT_FOUND, "支付单不存在");
        }
        if (operatorMemberId != null && !order.getMemberId().equals(operatorMemberId)) {
            throw new BizException(ErrorCode.BIZ_PERMISSION_DENIED, "无权操作他人支付单");
        }
        BigDecimal p = principal == null ? BigDecimal.ZERO : principal;
        BigDecimal g = gift == null ? BigDecimal.ZERO : gift;
        if (p.compareTo(BigDecimal.ZERO) < 0 || g.compareTo(BigDecimal.ZERO) < 0
                || p.add(g).compareTo(order.getAmount()) != 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "通知金额与订单不符");
        }
        int updated = payOrderMapper.casSuccess(payNo, wxTransactionId);
        if (updated == 0) {
            return false;
        }
        balanceService.recharge(order.getMemberId(), p, g, payNo);
        return true;
    }
}
