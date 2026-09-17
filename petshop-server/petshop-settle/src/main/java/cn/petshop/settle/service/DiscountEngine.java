package cn.petshop.settle.service;

import cn.petshop.common.web.BizException;
import cn.petshop.common.web.ErrorCode;
import lombok.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 折扣与取低引擎（纯函数）：规格原价 → 会员折扣 → 人工改价（最后生效，必填原因）。
 * 硬边界：充值与次卡购买不走本引擎；余额支付不影响折扣；特卖与会员折扣不叠加（取低，商品域接入后生效）。
 */
public final class DiscountEngine {

    private DiscountEngine() {
    }

    @Value
    public static class BillItem {
        String name;
        String category;
        BigDecimal amount;
    }

    @Value
    public static class Bill {
        BigDecimal total;
        BigDecimal levelDiscountApplied;
        boolean manualOverride;
    }

    public static Bill calc(List<BillItem> items, BigDecimal levelDiscount, BigDecimal manualPrice, String manualReason) {
        BigDecimal sum = items.stream()
                .map(BillItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        if (sum.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "结算明细金额非法");
        }
        BigDecimal applied = null;
        BigDecimal total = sum;
        if (levelDiscount != null && levelDiscount.compareTo(BigDecimal.ZERO) > 0
                && levelDiscount.compareTo(BigDecimal.ONE) < 0) {
            applied = levelDiscount;
            total = sum.multiply(levelDiscount).setScale(2, RoundingMode.HALF_UP);
        }
        boolean manual = false;
        if (manualPrice != null) {
            if (manualReason == null || manualReason.isBlank()) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "人工改价必须填写原因");
            }
            if (manualPrice.compareTo(BigDecimal.ZERO) < 0) {
                throw new BizException(ErrorCode.BIZ_PARAM_INVALID, "改价不能为负");
            }
            total = manualPrice.setScale(2, RoundingMode.HALF_UP);
            manual = true;
            // 人工改价把 total 整个覆盖掉了，会员折扣不再是这笔钱的定价依据 ——
            // 继续回填 levelDiscountApplied 会让 settle_bill.discount_desc 出现
            // 「会员折扣 9 折已生效」而实收是改价后的数，口径自相矛盾（对应审查 P3-2）。
            applied = null;
        }
        return new Bill(total, applied, manual);
    }
}
