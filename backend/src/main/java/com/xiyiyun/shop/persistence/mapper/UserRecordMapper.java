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
        INSERT INTO users (
            id, avatar, mobile, email, username, nickname, group_id, balance, deposit,
            real_name_type, real_name, subject_name, certificate_no, verification_status,
            status, last_login_at, created_at
        )
        VALUES (
            #{entity.id}, #{entity.avatar}, #{entity.mobile}, #{entity.email}, #{entity.username},
            #{entity.nickname}, #{entity.groupId}, #{entity.balance}, #{entity.deposit},
            #{entity.realNameType}, #{entity.realName}, #{entity.subjectName}, #{entity.certificateNo},
            #{entity.verificationStatus}, #{entity.status}, #{entity.lastLoginAt}, #{entity.createdAt}
        )
        ON DUPLICATE KEY UPDATE
            avatar = VALUES(avatar),
            mobile = VALUES(mobile),
            email = VALUES(email),
            username = VALUES(username),
            nickname = VALUES(nickname),
            group_id = VALUES(group_id),
            balance = VALUES(balance),
            deposit = VALUES(deposit),
            real_name_type = VALUES(real_name_type),
            real_name = VALUES(real_name),
            subject_name = VALUES(subject_name),
            certificate_no = VALUES(certificate_no),
            verification_status = VALUES(verification_status),
            status = VALUES(status),
            last_login_at = VALUES(last_login_at),
            deleted_at = NULL
        """)
    int upsertSnapshot(@Param("entity") UserRecordEntity entity);

    @Select("""
        SELECT id, avatar, mobile, email, username, nickname, group_id, balance, deposit,
               real_name_type, real_name, subject_name, certificate_no, verification_status,
               status, last_login_at, created_at
        FROM users
        WHERE deleted_at IS NULL
        ORDER BY id
        """)
    List<UserRecordEntity> selectActiveSnapshots();

    /**
     * 批次8C：会员<b>分页</b>快照。
     *
     * <p>原来的 {@code selectActiveSnapshots()} 会把每个会员的
     * {@code real_name}、{@code subject_name}、{@code certificate_no}（实名信息与证件号）
     * 连同余额一起读出来 —— 即打开一次会员列表就把<b>全站会员的实名资料</b>整份拉进 JVM 堆。
     * 这既是内存问题，也放大了敏感数据的暴露面：堆转储、GC 日志、异常快照里都会带上
     * 全量证件号。下推后一次只驻留一页。
     *
     * <p>{@code WHERE deleted_at IS NULL} 必须保留：users 是软删除表，
     * 少了这个条件会把已注销会员翻回列表，也会让 total 和实际可见行数不一致。
     *
     * <p>排序键保持原样的 {@code ORDER BY id}（不是 created_at DESC）。这是主键顺序，
     * 本身唯一，翻页天然稳定；改成别的键会让既有页面顺序发生肉眼可见的变化。
     */
    @Select("""
        SELECT id, avatar, mobile, email, username, nickname, group_id, balance, deposit,
               real_name_type, real_name, subject_name, certificate_no, verification_status,
               status, last_login_at, created_at
        FROM users
        WHERE deleted_at IS NULL
        ORDER BY id
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<UserRecordEntity> selectActiveSnapshotPage(@Param("limit") int limit, @Param("offset") long offset);

    /** 批次8C：与 {@link #selectActiveSnapshotPage} 使用完全相同的 WHERE（含软删除过滤），供分页返回 total。 */
    @Select("SELECT COUNT(*) FROM users WHERE deleted_at IS NULL")
    long countSnapshots();
}
