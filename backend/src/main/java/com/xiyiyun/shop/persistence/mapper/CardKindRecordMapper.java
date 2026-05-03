package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.CardKindRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CardKindRecordMapper extends BaseMapper<CardKindRecordEntity> {
    @Update("""
        INSERT INTO card_kinds (id, name, type, cost)
        VALUES (#{entity.id}, #{entity.name}, #{entity.type}, #{entity.cost})
        ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            type = VALUES(type),
            cost = VALUES(cost),
            deleted_at = NULL
        """)
    int upsertSnapshot(@Param("entity") CardKindRecordEntity entity);

    @Select("""
        SELECT id, name, type, cost
        FROM card_kinds
        WHERE deleted_at IS NULL
        ORDER BY id
        """)
    List<CardKindRecordEntity> selectActiveSnapshots();
}
