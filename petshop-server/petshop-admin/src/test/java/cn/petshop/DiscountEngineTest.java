package cn.petshop;

import cn.petshop.common.web.BizException;
import cn.petshop.settle.service.DiscountEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiscountEngine 纯函数单测（对应审查「测试覆盖评估」建议的最小测试集 ④）。
 * 零依赖、零 Spring 上下文，是性价比最高的回归防线。
 */
class DiscountEngineTest {

    private static DiscountEngine.BillItem item(String name, String amount) {
        return new DiscountEngine.BillItem(name, "WASH", new BigDecimal(amount));
    }

    @Test
    void noDiscountReturnsRawSum() {
        DiscountEngine.Bill bill = DiscountEngine.calc(
                List.of(item("洗护", "100"), item("美容", "50")), null, null, null);
        assertEquals(0, new BigDecimal("150.00").compareTo(bill.getTotal()));
        assertNull(bill.getLevelDiscountApplied());
        assertFalse(bill.isManualOverride());
    }

    @Test
    void zeroOrNegativeAmountRejected() {
        assertThrows(BizException.class, () -> DiscountEngine.calc(List.of(), null, null, null));
        assertThrows(BizException.class, () -> DiscountEngine.calc(
                List.of(item("洗护", "0")), null, null, null));
        assertThrows(BizException.class, () -> DiscountEngine.calc(
                List.of(item("洗护", "-10")), null, null, null));
    }

    /** 折扣边界：0 与 1 都不应被当作"有效折扣"，否则会把订单打成 0 元。 */
    @Test
    void discountBoundariesZeroAndOneAreIgnored() {
        DiscountEngine.Bill zero = DiscountEngine.calc(
                List.of(item("洗护", "100")), BigDecimal.ZERO, null, null);
        assertEquals(0, new BigDecimal("100.00").compareTo(zero.getTotal()));
        assertNull(zero.getLevelDiscountApplied());

        DiscountEngine.Bill one = DiscountEngine.calc(
                List.of(item("洗护", "100")), BigDecimal.ONE, null, null);
        assertEquals(0, new BigDecimal("100.00").compareTo(one.getTotal()));
        assertNull(one.getLevelDiscountApplied());
    }

    @Test
    void validDiscountAppliesWithHalfUpRounding() {
        DiscountEngine.Bill bill = DiscountEngine.calc(
                List.of(item("洗护", "99.99")), new BigDecimal("0.85"), null, null);
        // 99.99 * 0.85 = 84.9915 -> HALF_UP -> 84.99
        assertEquals(0, new BigDecimal("84.99").compareTo(bill.getTotal()));
        assertEquals(0, new BigDecimal("0.85").compareTo(bill.getLevelDiscountApplied()));
    }

    /** 折扣大于 1（溢价）不生效，避免出现"折扣后更贵"的荒谬结果。 */
    @Test
    void discountGreaterThanOneIsIgnored() {
        DiscountEngine.Bill bill = DiscountEngine.calc(
                List.of(item("洗护", "100")), new BigDecimal("1.5"), null, null);
        assertEquals(0, new BigDecimal("100.00").compareTo(bill.getTotal()));
        assertNull(bill.getLevelDiscountApplied());
    }

    @Test
    void manualPriceRequiresReason() {
        assertThrows(BizException.class, () -> DiscountEngine.calc(
                List.of(item("洗护", "100")), null, new BigDecimal("80"), null));
        assertThrows(BizException.class, () -> DiscountEngine.calc(
                List.of(item("洗护", "100")), null, new BigDecimal("80"), "   "));
    }

    @Test
    void manualPriceOverridesDiscountAndRejectsNegative() {
        DiscountEngine.Bill bill = DiscountEngine.calc(
                List.of(item("洗护", "100")), new BigDecimal("0.9"), new BigDecimal("70"), "会员生日福利");
        assertEquals(0, new BigDecimal("70.00").compareTo(bill.getTotal()));
        assertTrue(bill.isManualOverride());
        // 改价把金额整个覆盖了，会员折扣不再是定价依据，不能留在单据描述里
        // 出现「会员折扣已生效 + 实收改价后的数」这种自相矛盾（对应审查 P3-2）。
        assertNull(bill.getLevelDiscountApplied());

        assertThrows(BizException.class, () -> DiscountEngine.calc(
                List.of(item("洗护", "100")), null, new BigDecimal("-1"), "误操作"));
    }
}
