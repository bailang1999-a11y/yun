package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.CategoryRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CategoryRecordMapper extends BaseMapper<CategoryRecordEntity> {
    @Update("""
        INSERT INTO categories (id, parent_id, name, sort_no, status)
        VALUES (#{entity.id}, #{entity.parentId}, #{entity.name}, #{entity.sortNo}, #{entity.status})
        ON DUPLICATE KEY UPDATE
            parent_id = VALUES(parent_id),
            name = VALUES(name),
            sort_no = VALUES(sort_no),
            status = VALUES(status),
            deleted_at = NULL
        """)
    int upsertSnapshot(@Param("entity") CategoryRecordEntity entity);

    @Update("UPDATE categories SET deleted_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int softDelete(@Param("id") Long id);

    @Select("""
        SELECT id, parent_id, name, sort_no, status
        FROM categories
        WHERE deleted_at IS NULL
        ORDER BY sort_no, id
        """)
    List<CategoryRecordEntity> selectActiveSnapshots();
}
