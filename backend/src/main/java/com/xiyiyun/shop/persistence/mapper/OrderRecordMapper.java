package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.OrderRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OrderRecordMapper extends BaseMapper<OrderRecordEntity> {
    @Select("SELECT id FROM orders WHERE order_no = #{orderNo} AND deleted_at IS NULL LIMIT 1")
    Long findIdByOrderNo(@Param("orderNo") String orderNo);

    @Select("""
        SELECT id, order_no, user_id, source_platform_id, source_platform_code,
               goods_id, goods_name, goods_type, quantity, unit_price, total_amount,
               pay_amount, cost_amount, status, delivery_status, recharge_account,
               buyer_remark, admin_remark, request_id, paid_at, delivered_at, closed_at, created_at
        FROM orders
        WHERE deleted_at IS NULL
        ORDER BY created_at DESC, id DESC
        """)
    List<OrderRecordEntity> selectActiveSnapshots();

    @Select("""
        SELECT id, order_no, user_id, source_platform_id, source_platform_code,
               goods_id, goods_name, goods_type, quantity, unit_price, total_amount,
               pay_amount, cost_amount, status, delivery_status, recharge_account,
               buyer_remark, admin_remark, request_id, paid_at, delivered_at, closed_at, created_at
        FROM orders
        WHERE order_no = #{orderNo}
          AND deleted_at IS NULL
        LIMIT 1
        """)
    OrderRecordEntity findByOrderNo(@Param("orderNo") String orderNo);

    @Update("""
        INSERT INTO orders (
            order_no, user_id, source_platform_id, source_platform_code, goods_id,
            goods_name, goods_type, quantity, unit_price, total_amount, pay_amount,
            cost_amount, status, delivery_status, recharge_account, buyer_remark,
            admin_remark, request_id, paid_at, delivered_at, closed_at, created_at
        ) VALUES (
            #{entity.orderNo}, #{entity.userId}, #{entity.sourcePlatformId}, #{entity.sourcePlatformCode}, #{entity.goodsId},
            #{entity.goodsName}, #{entity.goodsType}, #{entity.quantity}, #{entity.unitPrice}, #{entity.totalAmount}, #{entity.payAmount},
            #{entity.costAmount}, #{entity.status}, #{entity.deliveryStatus}, #{entity.rechargeAccount}, #{entity.buyerRemark},
            #{entity.adminRemark}, #{entity.requestId}, #{entity.paidAt}, #{entity.deliveredAt}, #{entity.closedAt}, #{entity.createdAt}
        )
        ON DUPLICATE KEY UPDATE
            source_platform_code = VALUES(source_platform_code),
            goods_name = VALUES(goods_name),
            goods_type = VALUES(goods_type),
            quantity = VALUES(quantity),
            unit_price = VALUES(unit_price),
            total_amount = VALUES(total_amount),
            pay_amount = VALUES(pay_amount),
            cost_amount = VALUES(cost_amount),
            status = VALUES(status),
            delivery_status = VALUES(delivery_status),
            recharge_account = VALUES(recharge_account),
            buyer_remark = VALUES(buyer_remark),
            admin_remark = VALUES(admin_remark),
            paid_at = VALUES(paid_at),
            delivered_at = VALUES(delivered_at),
            closed_at = VALUES(closed_at)
        """)
    int upsertByOrderNo(@Param("entity") OrderRecordEntity entity);
}
