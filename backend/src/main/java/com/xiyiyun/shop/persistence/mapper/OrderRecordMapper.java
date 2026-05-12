package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.OrderRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
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
               goods_id, goods_name, goods_type, order_ip, order_ip_location, quantity, unit_price, total_amount,
               pay_amount, cost_amount, status, delivery_status, delivery_message,
               delivery_items_json, channel_attempts_json, recharge_account,
               buyer_remark, admin_remark, request_id, paid_at, delivered_at, closed_at, created_at
        FROM orders
        WHERE deleted_at IS NULL
        ORDER BY created_at DESC, id DESC
        """)
    List<OrderRecordEntity> selectActiveSnapshots();

    @Select("""
        SELECT id, order_no, user_id, source_platform_id, source_platform_code,
               goods_id, goods_name, goods_type, order_ip, order_ip_location, quantity, unit_price, total_amount,
               pay_amount, cost_amount, status, delivery_status, delivery_message,
               delivery_items_json, channel_attempts_json, recharge_account,
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
            goods_name, goods_type, order_ip, order_ip_location, quantity, unit_price, total_amount, pay_amount,
            cost_amount, status, delivery_status, delivery_message, delivery_items_json,
            channel_attempts_json, recharge_account, buyer_remark, admin_remark,
            request_id, paid_at, delivered_at, closed_at, created_at
        ) VALUES (
            #{entity.orderNo}, #{entity.userId}, #{entity.sourcePlatformId}, #{entity.sourcePlatformCode}, #{entity.goodsId},
            #{entity.goodsName}, #{entity.goodsType}, #{entity.orderIp}, #{entity.orderIpLocation}, #{entity.quantity}, #{entity.unitPrice}, #{entity.totalAmount}, #{entity.payAmount},
            #{entity.costAmount}, #{entity.status}, #{entity.deliveryStatus}, #{entity.deliveryMessage}, #{entity.deliveryItemsJson},
            #{entity.channelAttemptsJson}, #{entity.rechargeAccount}, #{entity.buyerRemark}, #{entity.adminRemark},
            #{entity.requestId}, #{entity.paidAt}, #{entity.deliveredAt}, #{entity.closedAt}, #{entity.createdAt}
        )
        ON DUPLICATE KEY UPDATE
            source_platform_code = VALUES(source_platform_code),
            goods_name = VALUES(goods_name),
            goods_type = VALUES(goods_type),
            order_ip = VALUES(order_ip),
            order_ip_location = VALUES(order_ip_location),
            quantity = VALUES(quantity),
            unit_price = VALUES(unit_price),
            total_amount = VALUES(total_amount),
            pay_amount = VALUES(pay_amount),
            cost_amount = VALUES(cost_amount),
            status = VALUES(status),
            delivery_status = VALUES(delivery_status),
            delivery_message = VALUES(delivery_message),
            delivery_items_json = VALUES(delivery_items_json),
            channel_attempts_json = VALUES(channel_attempts_json),
            recharge_account = VALUES(recharge_account),
            buyer_remark = VALUES(buyer_remark),
            admin_remark = VALUES(admin_remark),
            paid_at = VALUES(paid_at),
            delivered_at = VALUES(delivered_at),
            closed_at = VALUES(closed_at)
        """)
    int upsertByOrderNo(@Param("entity") OrderRecordEntity entity);

    @Select("""
        SELECT COUNT(*)
        FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'orders'
          AND column_name = #{columnName}
        """)
    int countOrderColumn(@Param("columnName") String columnName);

    @Update("ALTER TABLE orders ADD COLUMN delivery_message MEDIUMTEXT NULL AFTER delivery_status")
    int ensureDeliveryMessageColumn();

    @Update("ALTER TABLE orders ADD COLUMN delivery_items_json JSON NULL AFTER delivery_message")
    int ensureDeliveryItemsColumn();

    @Update("ALTER TABLE orders ADD COLUMN channel_attempts_json JSON NULL AFTER delivery_items_json")
    int ensureChannelAttemptsColumn();

    @Update("ALTER TABLE orders ADD COLUMN order_ip VARCHAR(64) NULL AFTER goods_type")
    int ensureOrderIpColumn();

    @Update("ALTER TABLE orders ADD COLUMN order_ip_location VARCHAR(128) NULL AFTER order_ip")
    int ensureOrderIpLocationColumn();

    @Delete("DELETE FROM order_status_logs WHERE order_no = #{orderNo}")
    int hardDeleteStatusLogsByOrderNo(@Param("orderNo") String orderNo);

    @Delete("DELETE FROM delivery_tasks WHERE order_no = #{orderNo}")
    int hardDeleteDeliveryTasksByOrderNo(@Param("orderNo") String orderNo);

    @Delete("DELETE FROM orders WHERE order_no = #{orderNo}")
    int hardDeleteByOrderNo(@Param("orderNo") String orderNo);
}
