package cn.petshop.wallet.mapper;

import cn.petshop.wallet.entity.MemberAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

public interface MemberAccountMapper extends BaseMapper<MemberAccount> {

    @Update("UPDATE member SET frozen_amount = frozen_amount + #{amt} WHERE id = #{memberId} AND balance - frozen_amount >= #{amt}")
    int freeze(@Param("memberId") Long memberId, @Param("amt") BigDecimal amt);

    @Update("UPDATE member SET frozen_amount = frozen_amount - #{amt} WHERE id = #{memberId} AND frozen_amount >= #{amt}")
    int unfreeze(@Param("memberId") Long memberId, @Param("amt") BigDecimal amt);

    @Update("UPDATE member SET balance = balance - #{amt} WHERE id = #{memberId} AND balance - frozen_amount >= #{amt}")
    int deductAvailable(@Param("memberId") Long memberId, @Param("amt") BigDecimal amt);

    @Update("UPDATE member SET balance = balance + #{principal} + #{gift}, cum_principal = cum_principal + #{principal}, cum_gift = cum_gift + #{gift} WHERE id = #{memberId}")
    int recharge(@Param("memberId") Long memberId, @Param("principal") BigDecimal principal, @Param("gift") BigDecimal gift);

    /**
     * 退款扣减：条件更新，保证 refAmt(=|principal|+|gift|) 不超过可用余额，
     * 同时把 cum_principal / cum_gift 向下扣减且不低于 0（防止历史累计为负导致后续分摊比例错乱）。
     */
    @Update("UPDATE member SET balance = balance - #{refAmt}, "
            + "cum_principal = CASE WHEN cum_principal >= #{principalAbs} THEN cum_principal - #{principalAbs} ELSE 0 END, "
            + "cum_gift = CASE WHEN cum_gift >= #{giftAbs} THEN cum_gift - #{giftAbs} ELSE 0 END "
            + "WHERE id = #{memberId} AND balance - frozen_amount >= #{refAmt}")
    int refund(@Param("memberId") Long memberId,
               @Param("refAmt") BigDecimal refAmt,
               @Param("principalAbs") BigDecimal principalAbs,
               @Param("giftAbs") BigDecimal giftAbs);

    /**
     * 退款入账：把退款金额加回可用余额。
     *
     * 注意这里<b>不动</b> cum_principal / cum_gift —— 这两个字段的语义是"历史真金白银充进来的钱"
     * （充值 +，退款出账 -），退款入余额是钱在账户体系内部回流，不是新的充值，计进去会虚增「可退本金」。
     * 无条件更新：加款不存在余额不足问题，原子性由行锁保证。
     */
    @Update("UPDATE member SET balance = balance + #{amt} WHERE id = #{memberId}")
    int refundIn(@Param("memberId") Long memberId, @Param("amt") BigDecimal amt);

    @Select("SELECT * FROM member WHERE id = #{memberId} FOR UPDATE")
    MemberAccount selectForUpdate(@Param("memberId") Long memberId);
}
