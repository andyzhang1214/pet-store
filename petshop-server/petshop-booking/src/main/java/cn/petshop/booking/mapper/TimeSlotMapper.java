package cn.petshop.booking.mapper;

import cn.petshop.booking.entity.TimeSlot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalTime;

public interface TimeSlotMapper extends BaseMapper<TimeSlot> {

    @Select("SELECT * FROM time_slot WHERE store_id = #{storeId} AND service_id = #{serviceId} AND slot_date = #{slotDate} AND start_time = #{startTime} FOR UPDATE")
    TimeSlot lockByNaturalKey(@Param("storeId") Long storeId, @Param("serviceId") Long serviceId,
                              @Param("slotDate") LocalDate slotDate, @Param("startTime") LocalTime startTime);

    @Select("SELECT * FROM time_slot WHERE store_id = #{storeId} AND service_id = #{serviceId} AND slot_date = #{slotDate} AND start_time = #{startTime}")
    TimeSlot findByNaturalKey(@Param("storeId") Long storeId, @Param("serviceId") Long serviceId,
                              @Param("slotDate") LocalDate slotDate, @Param("startTime") LocalTime startTime);

    @Update("UPDATE time_slot SET booked_count = booked_count + 1 WHERE id = #{id}")
    int incrBooked(@Param("id") Long id);

    @Update("UPDATE time_slot SET booked_count = booked_count - 1 WHERE id = #{id} AND booked_count > 0")
    int decrBooked(@Param("id") Long id);

    /** 按日期取 slotId 列表：替代 inSql 字符串拼接（对应审查 MINOR-2）。 */
    @Select("SELECT id FROM time_slot WHERE slot_date = #{date}")
    java.util.List<Long> selectIdsByDate(@Param("date") LocalDate date);

    /** 容量回写：锁内发现门店配置容量已变时同步落库，避免下一个请求读到旧容量（对应审查 MINOR-7）。 */
    @Update("UPDATE time_slot SET capacity = #{capacity} WHERE id = #{id} AND capacity <> #{capacity}")
    int syncCapacity(@Param("id") Long id, @Param("capacity") int capacity);
}
