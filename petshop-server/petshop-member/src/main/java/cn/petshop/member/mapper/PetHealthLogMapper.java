package cn.petshop.member.mapper;

import cn.petshop.member.entity.PetHealthLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

public interface PetHealthLogMapper extends BaseMapper<PetHealthLog> {

    @org.apache.ibatis.annotations.Select("SELECT * FROM pet_health_log WHERE pet_id = #{petId} AND log_type = #{type} ORDER BY log_date DESC, id DESC LIMIT 1")
    PetHealthLog selectLatest(@Param("petId") Long petId, @Param("type") String type);
}
