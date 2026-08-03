package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.GoodsPrimaryChannelProjection;
import com.xiyiyun.shop.persistence.entity.SupplierPriceHistoryEntity;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SupplierPriceHistoryMapper extends BaseMapper<SupplierPriceHistoryEntity> {
    @Select("""
        SELECT id
        FROM goods_channels
        WHERE id = #{channelId}
          AND deleted_at IS NULL
        FOR UPDATE
        """)
    Long lockChannel(@Param("channelId") Long channelId);

    @Select("""
        SELECT id, goods_id, channel_id, supplier_id, supplier_name, supplier_goods_id,
               unit_price, previous_unit_price, change_amount, direction, observed_at, created_at
        FROM supplier_price_history
        WHERE channel_id = #{channelId}
        ORDER BY observed_at DESC, id DESC
        LIMIT 1
        """)
    SupplierPriceHistoryEntity selectLatestByChannelId(@Param("channelId") Long channelId);

    @Insert("""
        INSERT INTO supplier_price_history (
            goods_id, channel_id, supplier_id, supplier_name, supplier_goods_id,
            unit_price, previous_unit_price, change_amount, direction, observed_at
        ) VALUES (
            #{entity.goodsId}, #{entity.channelId}, #{entity.supplierId}, #{entity.supplierName},
            #{entity.supplierGoodsId}, #{entity.unitPrice}, #{entity.previousUnitPrice},
            #{entity.changeAmount}, #{entity.direction}, #{entity.observedAt}
        )
        """)
    int insertSnapshot(@Param("entity") SupplierPriceHistoryEntity entity);

    @Select("""
        <script>
        SELECT goods_id AS goodsId, id AS channelId
        FROM (
          SELECT goods_id, id, ROW_NUMBER() OVER (PARTITION BY goods_id ORDER BY priority, id) AS row_num
          FROM goods_channels
          WHERE deleted_at IS NULL
            AND status = 'ENABLED'
            AND goods_id IN
            <foreach collection="goodsIds" item="goodsId" open="(" separator="," close=")">
              #{goodsId}
            </foreach>
        ) ranked
        WHERE row_num = 1
        </script>
        """)
    List<GoodsPrimaryChannelProjection> selectPrimaryChannels(@Param("goodsIds") List<Long> goodsIds);

    @Select("""
        <script>
        SELECT id, goods_id, channel_id, supplier_id, supplier_name, supplier_goods_id,
               unit_price, previous_unit_price, change_amount, direction, observed_at, created_at
        FROM (
          SELECT id, goods_id, channel_id, supplier_id, supplier_name, supplier_goods_id,
                 unit_price, previous_unit_price, change_amount, direction, observed_at, created_at,
                 ROW_NUMBER() OVER (PARTITION BY channel_id ORDER BY observed_at DESC, id DESC) AS row_num
          FROM supplier_price_history
          WHERE channel_id IN
          <foreach collection="channelIds" item="channelId" open="(" separator="," close=")">
            #{channelId}
          </foreach>
        ) recent
        WHERE row_num &lt;= 10
        ORDER BY channel_id, observed_at, id
        </script>
        """)
    List<SupplierPriceHistoryEntity> selectRecentByChannelIds(@Param("channelIds") List<Long> channelIds);
}
