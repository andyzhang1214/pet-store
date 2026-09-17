package cn.petshop.mall.mapper;

import cn.petshop.mall.entity.Sku;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface SkuMapper extends BaseMapper<Sku> {

    /**
     * 扣库存 CAS：{@code stock >= qty} 作为条件写在 WHERE 里，由数据库行锁保证原子性。
     * 返回 0 表示库存不足，调用方必须据此整单失败回滚 —— <b>不能先 SELECT 判断再 UPDATE</b>，
     * 那样两个并发下单会同时通过判断（典型的 check-then-act 超卖）。
     */
    @Update("UPDATE sku SET stock = stock - #{qty} WHERE id = #{skuId} AND stock >= #{qty}")
    int deductStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /** 回补库存（取消 / 退款）。无条件加回，不加 {@code stock >= 0} 之类的下界条件。 */
    @Update("UPDATE sku SET stock = stock + #{qty} WHERE id = #{skuId}")
    int restoreStock(@Param("skuId") Long skuId, @Param("qty") int qty);

    /**
     * 人工盘点调整库存：{@code delta} 可正可负，但结果不允许为负。
     *
     * <p>把 {@code stock + delta >= 0} 写在 WHERE 里，而不是先查当前库存再判断能不能减：
     * 后者是典型的 check-then-act —— 两个店员同时各减 5 件、库存剩 8 件时，
     * 两边都会看到"够减"，结果库存变成 -2。
     * 返回 0 表示会减成负数，调用方必须据此拒绝，而不是当作"改了 0 件"。
     */
    @Update("UPDATE sku SET stock = stock + #{delta} WHERE id = #{skuId} AND stock + #{delta} >= 0")
    int adjustStock(@Param("skuId") Long skuId, @Param("delta") int delta);
}
