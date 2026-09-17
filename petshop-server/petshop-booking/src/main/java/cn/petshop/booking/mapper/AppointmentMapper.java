package cn.petshop.booking.mapper;

import cn.petshop.booking.entity.Appointment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

public interface AppointmentMapper extends BaseMapper<Appointment> {

    @Select("SELECT COUNT(*) FROM appointment WHERE slot_id = #{slotId} AND pet_id = #{petId} AND status IN ('PENDING','ARRIVED')")
    int countActiveBySlotAndPet(@Param("slotId") Long slotId, @Param("petId") Long petId);

    @Update("UPDATE appointment SET status = 'ARRIVED', updated_at = NOW() WHERE verify_code = #{code} AND status = 'PENDING'")
    int casArrivedByVerifyCode(@Param("code") String code);

    /**
     * 顾客自助确认到店：归属 + 状态 CAS（member_id 保证只能确认自己的预约）。
     * 「仅预约当天」的日期校验在 Service 层做 —— 预约创建后 slot 日期不可变，无竞态窗口；
     * 次日起的兜底由 AppointmentAutoConfirmJob 处理。
     */
    @Update("UPDATE appointment SET status = 'ARRIVED', updated_at = NOW() "
            + "WHERE id = #{id} AND member_id = #{memberId} AND status = 'PENDING'")
    int casArrivedByIdAndMember(@Param("id") Long id, @Param("memberId") Long memberId);

    @Update("UPDATE appointment SET status = 'ARRIVED', updated_at = NOW() WHERE id = #{id} AND status = 'PENDING'")
    int casArrivedById(@Param("id") Long id);

    @Select("SELECT a.id FROM appointment a JOIN time_slot t ON a.slot_id = t.id "
            + "WHERE a.status = 'PENDING' AND t.slot_date < #{date} ORDER BY a.id LIMIT #{limit}")
    List<Long> listPendingIdsBeforeDate(@Param("date") LocalDate date, @Param("limit") int limit);

    @Select("SELECT * FROM appointment WHERE verify_code = #{code}")
    Appointment selectByVerifyCode(@Param("code") String code);

    /**
     * 状态机 CAS：仅当当前状态为 from 时才迁移到 to，返回受影响行数。
     * 用于结算抢占（ARRIVED → SETTLING），并发双击时只有一个事务能成功。
     */
    @Update("UPDATE appointment SET status = #{to}, updated_at = NOW() WHERE id = #{id} AND status = #{from}")
    int casStatus(@Param("id") Long id, @Param("from") String from, @Param("to") String to);
}
