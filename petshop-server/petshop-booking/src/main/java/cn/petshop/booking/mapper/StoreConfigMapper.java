package cn.petshop.booking.mapper;

import cn.petshop.booking.entity.StoreConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface StoreConfigMapper extends BaseMapper<StoreConfig> {

    @Select("SELECT * FROM store_config WHERE store_id = #{storeId}")
    StoreConfig selectByStoreId(@Param("storeId") Long storeId);
}
