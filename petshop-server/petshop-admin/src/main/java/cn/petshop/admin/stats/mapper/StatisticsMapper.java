package cn.petshop.admin.stats.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据统计聚合查询。
 *
 * <p>只读、跨业务表的聚合 SQL 集中放在 admin 模块（组合根），不散落进各业务模块 ——
 * 统计口径是「后台报表」的关切，改动它不应该惊动 wallet / mall 的单测。</p>
 *
 * <p>SQL 兼容 H2 与 MySQL：只用了 DATE() / COALESCE / SUM 等两者行为一致的函数。
 * 日期一律以 {@code DATE(created_at)} 落桶，与业务侧「自然日」口径一致；
 * 趋势序列的补零在 {@code StatisticsService} 里做，不依赖数据库的日期生成函数。</p>
 */
public interface StatisticsMapper {

    /**
     * 到店结算按日聚合（含支付方式维度）。
     * pay_type = CARD 表示次卡抵扣结算 —— 次卡的现金收入发生在售卡/充值日，
     * 所以「今日营收」要把 CARD 单金额计入服务营收，但单列笔数供口径说明。
     */
    @Select("SELECT DATE(created_at) AS d, pay_type AS pt, COUNT(*) AS cnt, "
            + "COALESCE(SUM(total), 0) AS amt FROM settle_bill "
            + "WHERE created_at >= #{from} GROUP BY DATE(created_at), pay_type")
    List<Map<String, Object>> settleDailyByPayType(@Param("from") LocalDateTime from);

    /**
     * 商品销售按日聚合。营收口径 = 已支付（PAID 待自提）+ 已核销（VERIFIED），
     * CREATED 未付款、CANCELLED / REFUNDED 没有形成收入。
     */
    @Select("SELECT DATE(created_at) AS d, COUNT(*) AS cnt, COALESCE(SUM(total), 0) AS amt "
            + "FROM goods_order WHERE status IN ('PAID', 'VERIFIED') AND created_at >= #{from} "
            + "GROUP BY DATE(created_at)")
    List<Map<String, Object>> goodsDaily(@Param("from") LocalDateTime from);

    /** 今日充值：只统计实充入账（principal_amt > 0），赠送金单列展示，不与营收混算。 */
    @Select("SELECT COALESCE(SUM(principal_amt), 0) AS principal, COALESCE(SUM(gift_amt), 0) AS gift, "
            + "COUNT(*) AS cnt FROM balance_ledger "
            + "WHERE biz_type = 'RECHARGE' AND principal_amt > 0 AND created_at >= #{from}")
    Map<String, Object> rechargeSummary(@Param("from") LocalDateTime from);

    /** 今日预约按状态分布（预约日 = 时段日期，不是下单日期）。 */
    @Select("SELECT a.status AS s, COUNT(*) AS cnt FROM appointment a "
            + "JOIN time_slot t ON a.slot_id = t.id WHERE t.slot_date = #{date} GROUP BY a.status")
    List<Map<String, Object>> apptCountByStatus(@Param("date") LocalDate date);

    /** 会员资产总览：member 表即会员账户（balance / frozen_amount）。 */
    @Select("SELECT COUNT(*) AS total, COALESCE(SUM(balance), 0) AS balance, "
            + "COALESCE(SUM(frozen_amount), 0) AS frozen FROM member")
    Map<String, Object> memberSummary();

    @Select("SELECT COUNT(*) FROM member WHERE created_at >= #{from}")
    long newMembersSince(@Param("from") LocalDateTime from);
}
