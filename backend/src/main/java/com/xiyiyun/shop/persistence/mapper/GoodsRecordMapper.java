package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.GoodsRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface GoodsRecordMapper extends BaseMapper<GoodsRecordEntity> {
    @Update("""
        INSERT INTO goods (
            id, category_id, name, goods_type, status, face_value, sale_price,
            cost_price, stock_mode, stock_count, min_qty, max_qty, delivery_template,
            sort_no, description, created_at
        ) VALUES (
            #{entity.id}, #{entity.categoryId}, #{entity.name}, #{entity.goodsType}, #{entity.status}, #{entity.faceValue}, #{entity.salePrice},
            #{entity.costPrice}, #{entity.stockMode}, #{entity.stockCount}, #{entity.minQty}, #{entity.maxQty}, #{entity.deliveryTemplate},
            #{entity.sortNo}, #{entity.description}, #{entity.createdAt}
        )
        ON DUPLICATE KEY UPDATE
            category_id = VALUES(category_id),
            name = VALUES(name),
            goods_type = VALUES(goods_type),
            status = VALUES(status),
            face_value = VALUES(face_value),
            sale_price = VALUES(sale_price),
            cost_price = VALUES(cost_price),
            stock_mode = VALUES(stock_mode),
            stock_count = VALUES(stock_count),
            min_qty = VALUES(min_qty),
            max_qty = VALUES(max_qty),
            delivery_template = VALUES(delivery_template),
            description = VALUES(description),
            deleted_at = NULL
        """)
    int upsertSnapshot(@Param("entity") GoodsRecordEntity entity);

    @Select("""
        SELECT id, category_id, name, goods_type, status, face_value, sale_price,
               cost_price, stock_mode, stock_count, min_qty, max_qty, delivery_template,
               sort_no, description, created_at
        FROM goods
        WHERE deleted_at IS NULL
        ORDER BY id
        """)
    List<GoodsRecordEntity> selectActiveSnapshots();
}
