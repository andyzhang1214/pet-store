package cn.petshop.wallet.mapper;

import cn.petshop.wallet.entity.RefundApply;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface RefundApplyMapper extends BaseMapper<RefundApply> {

    /**
     * 纯状态迁移 CAS（用于撤回、驳回等不涉及金额回写的迁移）。
     * 返回 0 表示前置态已被别人改掉，调用方必须整体失败回滚。
     */
    @Update("UPDATE refund_apply SET status = #{to}, updated_at = #{at} "
            + "WHERE id = #{id} AND status = #{from}")
    int casStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to,
                  @Param("at") LocalDateTime at);

    /**
     * 终态迁移 CAS，同时回写审核信息与入余额的金额拆分。
     *
     * 这是重复入账的<b>主防线</b>：审核通过时先抢占 {@code APPLYING → REFUNDED}，
     * 并发双击只有第一个事务能拿到行锁并改成 REFUNDED，后者读到已变更状态、CAS 返回 0 被拒绝。
     */
    @Update("UPDATE refund_apply SET status = #{to}, principal_amt = #{principal}, gift_amt = #{gift}, "
            + "auditor = #{auditor}, audit_remark = #{remark}, finished_at = #{at}, updated_at = #{at} "
            + "WHERE id = #{id} AND status = #{from}")
    int casAudit(@Param("id") Long id, @Param("from") String from, @Param("to") String to,
                 @Param("principal") BigDecimal principal, @Param("gift") BigDecimal gift,
                 @Param("auditor") String auditor, @Param("remark") String remark,
                 @Param("at") LocalDateTime at);
}
