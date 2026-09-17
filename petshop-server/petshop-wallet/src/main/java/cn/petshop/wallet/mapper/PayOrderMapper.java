package cn.petshop.wallet.mapper;

import cn.petshop.wallet.entity.PayOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface PayOrderMapper extends BaseMapper<PayOrder> {

    @Update("UPDATE pay_order SET status = 'SUCCESS', wx_transaction_id = #{txId}, notify_count = notify_count + 1 WHERE pay_no = #{payNo} AND status = 'CREATED'")
    int casSuccess(@Param("payNo") String payNo, @Param("txId") String txId);
}
