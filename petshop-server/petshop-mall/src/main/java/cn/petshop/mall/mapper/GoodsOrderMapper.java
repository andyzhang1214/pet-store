package cn.petshop.mall.mapper;

import cn.petshop.mall.entity.GoodsOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品订单状态迁移。全部走 CAS（{@code WHERE status = 前置态}），没有一处"先查后改"。
 *
 * <p>时间一律由应用层按门店时区算好传入（{@code updatedAt}），SQL 内不出现
 * {@code NOW()} / {@code CURDATE()} —— 与全局时区硬规则一致，避免应用与库时区不一致时
 * 产生不可复现的边界问题。
 */
public interface GoodsOrderMapper extends BaseMapper<GoodsOrder> {

    /**
     * 支付成功：{@code CREATED → PAID}，同时落支付构成（本金 / 赠送金）。
     *
     * <p>金额构成在这里一次性写入而不是事后补，是为了让「订单金额 == 账本流水金额」这条对账
     * 恒等式从订单诞生就成立 —— 退款按订单退时不会因为对不上而算错。
     */
    @Update("UPDATE goods_order SET status = 'PAID', principal_paid = #{principal}, gift_paid = #{gift}, "
            + "pay_type = #{payType}, updated_at = #{updatedAt} "
            + "WHERE order_no = #{orderNo} AND member_id = #{memberId} AND status = 'CREATED'")
    int casPaid(@Param("orderNo") String orderNo, @Param("memberId") Long memberId,
                @Param("principal") BigDecimal principal, @Param("gift") BigDecimal gift,
                @Param("payType") String payType, @Param("updatedAt") LocalDateTime updatedAt);

    /** 取消：{@code CREATED → CANCELLED}。只能取消未支付的单，已支付走退款。 */
    @Update("UPDATE goods_order SET status = 'CANCELLED', updated_at = #{updatedAt} "
            + "WHERE order_no = #{orderNo} AND member_id = #{memberId} AND status = 'CREATED'")
    int casCancel(@Param("orderNo") String orderNo, @Param("memberId") Long memberId,
                  @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 超时关单：{@code CREATED → CANCELLED}，系统侧调用（无 member_id 条件）。
     *
     * <p><b>为什么 WHERE 里还要带一次 {@code created_at < deadline}</b>：
     * 关单任务先扫一遍「哪些单超时了」，再逐单执行这条 CAS —— 两步之间隔着网络与事务，
     * 完全可能出现「扫描时确实超时，执行时顾客刚把钱付了」。
     * 把超时判定放进 UPDATE 的条件里，由数据库在行锁下复核，就不会误关一张已支付的单。
     * 少写这个条件，等于用一次有竞态的先查后改决定别人的订单生死。
     */
    @Update("UPDATE goods_order SET status = 'CANCELLED', updated_at = #{updatedAt} "
            + "WHERE order_no = #{orderNo} AND status = 'CREATED' AND created_at < #{deadline}")
    int casExpire(@Param("orderNo") String orderNo, @Param("deadline") LocalDateTime deadline,
                  @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 核销（自提）：{@code PAID → VERIFIED}，按核销码定位。
     * 不加 member_id 条件 —— 核销是店员操作顾客的单，归属由核销码本身承载。
     */
    @Update("UPDATE goods_order SET status = 'VERIFIED', updated_at = #{updatedAt} "
            + "WHERE verify_code = #{verifyCode} AND status = 'PAID'")
    int casVerifyByCode(@Param("verifyCode") String verifyCode, @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 退款 CAS：{@code PAID → REFUNDED}，这是"同一订单不会被退两次"的<b>最终防线</b>。
     *
     * <p>即使并发产生了多张退款单（重复申请被绕过、或被人工重放），第一张审核后订单已变
     * {@code REFUNDED}，第二张的这次 CAS 会返回 0 使整个事务回滚，库存也只回补一次。
     *
     * <p>只放行 {@code PAID}（已支付未核销）。{@code VERIFIED}（已核销）不允许线上退 ——
     * 这是业务口径，与 PRD 4.3 一致。状态白名单必须与
     * {@code MallOrderService.isRefundable} 保持一致，否则会出现
     * 「申请能提交、审核必失败」的不一致（次卡退款就踩过这个坑）。
     */
    @Update("UPDATE goods_order SET status = 'REFUNDED', updated_at = #{updatedAt} "
            + "WHERE order_no = #{orderNo} AND member_id = #{memberId} AND status = 'PAID'")
    int casRefund(@Param("orderNo") String orderNo, @Param("memberId") Long memberId,
                  @Param("updatedAt") LocalDateTime updatedAt);
}
