package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.PaymentCallbackLogEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PaymentCallbackLogMapper extends BaseMapper<PaymentCallbackLogEntity> {
    @Select("""
        SELECT id, provider, payment_no, order_no, callback_status, channel_trade_no,
               result, message, raw_payload, created_at
        FROM payment_callback_logs
        ORDER BY created_at DESC, id DESC
        """)
    List<PaymentCallbackLogEntity> selectSnapshots();
}
