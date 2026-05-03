package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.GoodsChannelRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GoodsChannelRecordMapper extends BaseMapper<GoodsChannelRecordEntity> {
    @Update("""
        INSERT INTO goods_channels (
            id, goods_id, supplier_id, supplier_name, supplier_goods_id,
            priority, timeout_seconds, status, created_at
        ) VALUES (
            #{entity.id}, #{entity.goodsId}, #{entity.supplierId}, #{entity.supplierName}, #{entity.supplierGoodsId},
            #{entity.priority}, #{entity.timeoutSeconds}, #{entity.status}, #{entity.createdAt}
        )
        ON DUPLICATE KEY UPDATE
            supplier_id = VALUES(supplier_id),
            supplier_name = VALUES(supplier_name),
            supplier_goods_id = VALUES(supplier_goods_id),
            priority = VALUES(priority),
            timeout_seconds = VALUES(timeout_seconds),
            status = VALUES(status),
            deleted_at = NULL
        """)
    int upsertSnapshot(@Param("entity") GoodsChannelRecordEntity entity);

    @Update("UPDATE goods_channels SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int softDelete(@Param("id") Long id);

    @Update("UPDATE goods_channels SET deleted_at = CURRENT_TIMESTAMP(3) WHERE supplier_id = #{supplierId}")
    int softDeleteBySupplier(@Param("supplierId") Long supplierId);

    @Select("""
        SELECT id, goods_id, supplier_id, supplier_name, supplier_goods_id,
               priority, timeout_seconds, status, created_at
        FROM goods_channels
        WHERE deleted_at IS NULL
        ORDER BY goods_id, priority, id
        """)
    List<GoodsChannelRecordEntity> selectActiveSnapshots();
}
