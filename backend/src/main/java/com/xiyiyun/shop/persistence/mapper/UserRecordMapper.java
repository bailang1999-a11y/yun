package com.xiyiyun.shop.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiyiyun.shop.persistence.entity.UserRecordEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserRecordMapper extends BaseMapper<UserRecordEntity> {
    @Update("""
        INSERT INTO users (id, avatar, mobile, email, nickname, group_id, balance, status, last_login_at, created_at)
        VALUES (#{entity.id}, #{entity.avatar}, #{entity.mobile}, #{entity.email}, #{entity.nickname}, #{entity.groupId}, #{entity.balance}, #{entity.status}, #{entity.lastLoginAt}, #{entity.createdAt})
        ON DUPLICATE KEY UPDATE
            avatar = VALUES(avatar),
            mobile = VALUES(mobile),
            email = VALUES(email),
            nickname = VALUES(nickname),
            group_id = VALUES(group_id),
            balance = VALUES(balance),
            status = VALUES(status),
            last_login_at = VALUES(last_login_at),
            deleted_at = NULL
        """)
    int upsertSnapshot(@Param("entity") UserRecordEntity entity);

    @Select("""
        SELECT id, avatar, mobile, email, nickname, group_id, balance, status, last_login_at, created_at
        FROM users
        WHERE deleted_at IS NULL
        ORDER BY id
        """)
    List<UserRecordEntity> selectActiveSnapshots();
}
