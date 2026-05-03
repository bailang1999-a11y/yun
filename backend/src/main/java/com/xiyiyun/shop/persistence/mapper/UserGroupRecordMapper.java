package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.UserGroupRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserGroupRecordMapper extends BaseMapper<UserGroupRecordEntity> {
    @Update("""
        INSERT INTO user_groups (
            id, name, description, is_default, status, order_enabled, real_name_required_for_order
        ) VALUES (
            #{entity.id}, #{entity.name}, #{entity.description}, #{entity.defaultGroup}, #{entity.status},
            #{entity.orderEnabled}, #{entity.realNameRequiredForOrder}
        )
        ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            description = VALUES(description),
            is_default = VALUES(is_default),
            status = VALUES(status),
            order_enabled = VALUES(order_enabled),
            real_name_required_for_order = VALUES(real_name_required_for_order),
            deleted_at = NULL
        """)
    int upsertSnapshot(@Param("entity") UserGroupRecordEntity entity);

    @Select("""
        SELECT id, name, description, is_default AS default_group, status, order_enabled, real_name_required_for_order
        FROM user_groups
        WHERE deleted_at IS NULL
        ORDER BY id
        """)
    List<UserGroupRecordEntity> selectActiveSnapshots();
}
