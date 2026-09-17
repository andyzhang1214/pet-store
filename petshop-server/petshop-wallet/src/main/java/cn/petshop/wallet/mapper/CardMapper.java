package cn.petshop.wallet.mapper;

import cn.petshop.wallet.entity.Card;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

public interface CardMapper extends BaseMapper<Card> {

    @Update("UPDATE card SET pre_deduct_count = pre_deduct_count + 1 WHERE id = #{cardId} AND member_id = #{memberId} AND status = 'USING' AND valid_until >= #{today} AND remain_times - pre_deduct_count >= 1")
    int preDeduct(@Param("cardId") Long cardId, @Param("memberId") Long memberId, @Param("today") LocalDate today);

    @Update("UPDATE card SET remain_times = remain_times - 1, pre_deduct_count = pre_deduct_count - 1 WHERE id = #{cardId} AND pre_deduct_count >= 1 AND remain_times >= 1")
    int confirm(@Param("cardId") Long cardId);

    @Update("UPDATE card SET pre_deduct_count = pre_deduct_count - 1 WHERE id = #{cardId} AND pre_deduct_count >= 1")
    int release(@Param("cardId") Long cardId);

    /**
     * 次卡退款 CAS：只有「本人持有 + 状态为使用中或已过期 + 一次没用过（remain = total）且无预扣」才能整卡退款。
     *
     * 这条 SQL 是"同一张卡不会被退两次"的最终防线：即使并发产生了多张退款单，
     * 第一张审核后卡已变 REFUNDED，第二张的这次 CAS 会返回 0 使整个事务回滚。
     *
     * 状态白名单必须与 {@code CardService.isRefundable} 保持一致 —— 过期卡可退是业务口径（见该方法注释），
     * 这里若只放行 USING，过期卡就会卡在"申请能提交、审核必失败"的不一致状态。
     */
    @Update("UPDATE card SET status = 'REFUNDED', remain_times = 0, pre_deduct_count = 0 "
            + "WHERE id = #{cardId} AND member_id = #{memberId} AND status IN ('USING', 'EXPIRED') "
            + "AND remain_times = total_times AND pre_deduct_count = 0")
    int refundCascade(@Param("cardId") Long cardId, @Param("memberId") Long memberId);

    /**
     * 后台调次 CAS（补次 / 扣次）。
     *
     * <p>条件里带上 {@code expectRemain} 与 {@code expectTotal} 两个原值，
     * 而不是写成 {@code remain_times = remain_times + delta}：调次是「后台读到一个数、人工决定改成另一个数」，
     * 服务端必须断言"我看到的那个数现在还是那个数"。否则两个店员同时补次会各自基于旧值计算，
     * 一个的调整会被覆盖掉，而两边都收到成功提示。</p>
     *
     * <p>返回 0 表示这张卡在这期间被别人动过（或已被退款、已被核销），调用方应提示刷新而不是重试。</p>
     */
    @Update("UPDATE card SET remain_times = #{newRemain}, total_times = #{newTotal} "
            + "WHERE id = #{cardId} AND remain_times = #{expectRemain} AND total_times = #{expectTotal} "
            + "AND status = 'USING'")
    int casAdjustTimes(@Param("cardId") Long cardId,
                       @Param("expectRemain") int expectRemain,
                       @Param("expectTotal") int expectTotal,
                       @Param("newRemain") int newRemain,
                       @Param("newTotal") int newTotal);
}
